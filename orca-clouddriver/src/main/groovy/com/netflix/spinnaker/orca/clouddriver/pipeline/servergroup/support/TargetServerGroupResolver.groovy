/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.support

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.orca.clouddriver.OortService
import com.netflix.spinnaker.orca.pipeline.model.Stage
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import retrofit.RetrofitError
import retrofit.client.Response
import retrofit.converter.ConversionException
import retrofit.converter.JacksonConverter

@Component
@Slf4j
class TargetServerGroupResolver {

  @Autowired
  OortService oortService

  @Autowired
  ObjectMapper mapper

  List<TargetServerGroup> resolve(Stage stage) {
    resolveByParams(TargetServerGroup.Params.fromStage(stage))
  }

  List<TargetServerGroup> resolveByParams(TargetServerGroup.Params params) {
    if (!params) {
      log.warn "No TargetServerGroup.Params to resolveByParams"
      return []
    }

    return params.locations.collect { Location location ->
      if (params.target) {
        return resolveByTarget(params, location)
      } else if (params.asgName) {
        return resolveByAsgName(params, location)
      }
      throw new TargetServerGroup.NotFoundException("TargetServerGroup.Params must have either target or asgName")
    }
  }

  private TargetServerGroup resolveByTarget(TargetServerGroup.Params params, Location location) {
    Map tsgMap = fetchWithRetries(Map) {
      oortService.getTargetServerGroup(params.app,
        params.credentials,
        params.cluster,
        params.cloudProvider,
        location.value,
        params.target.name())
    }
    if (!tsgMap) {
      throw new TargetServerGroup.NotFoundException("Unable to locate ${params.target.name()} in $params.credentials/$location.value/$params.cluster")
    }
    return new TargetServerGroup(serverGroup: tsgMap)
  }

  private TargetServerGroup resolveByAsgName(TargetServerGroup.Params params, Location location) {
    List<Map> tsgList = fetchWithRetries(List) {
      oortService.getServerGroup(params.app,
        params.credentials,
        params.cluster,
        params.asgName,
        null /* region */, // TODO(ttomsu): Add zonal support to this op.
        params.cloudProvider)
    }
    // Without zonal support in the getServerGroup call above, we have to do the filtering here.
    def tsg = tsgList?.find { Map tsg -> tsg.region == location.value || tsg.zones?.contains(location.value) || tsg.namespace == location.value }
    if (!tsg) {
      throw new TargetServerGroup.NotFoundException("Unable to locate $params.asgName in $params.credentials/$location.value/$params.cluster")
    }
    return new TargetServerGroup(serverGroup: tsg)
  }

  /**
   * fromPreviousStage looks back at this execution's stages to find the stage at which the TargetServerGroups were
   * resolved.
   */
  static TargetServerGroup fromPreviousStage(Stage stage) {
    // The DetermineTargetServerGroupStage has all the TargetServerGroups we want - go find it!
    def dtsgStage = stage.execution.stages.find {
      isDTSGStage(it) && (sameParent(stage, it) || isParentOf(stage, it))
    }

    if (!dtsgStage) {
      throw new TargetServerGroup.NotFoundException("No DetermineServerGroupStage found for stage ${stage}")
    } else if (!dtsgStage.context.targetReferences){
      throw new TargetServerGroup.NotFoundException("No TargetServerGroups found for stage ${stage}")
    }
    List<TargetServerGroup> tsgs = dtsgStage.context.targetReferences.collect {
      return new TargetServerGroup(serverGroup: it)
    }
    if (!tsgs) {
      throw new TargetServerGroup.NotFoundException("No targetReferences found on DetermineTargetServerGroup stage " +
        "${stage}")
    }

    def tsg = tsgs.find {
      def location = it.getLocation().value
      return (stage.context.regions?.contains(location)) ||
        (stage.context.zones?.contains(location)) ||
        (stage.context.namespaces?.contains(location))
    }
    if (!tsg) {
      def locations = []
      stage.context.regions && locations << stage.context.regions
      stage.context.zones && locations << stage.context.zones
      stage.context.namespaces && locations << stage.context.namespaces
      throw new TargetServerGroup.NotFoundException("No targets found on matching any location in ${locations} in " +
        "target server groups: ${tsgs}")
    }
    return tsg
  }

  private static boolean isDTSGStage(Stage stage) {
    return stage.type == DetermineTargetServerGroupStage.PIPELINE_CONFIG_TYPE
  }

  private static boolean sameParent(Stage a, Stage b) {
    return a.parentStageId == b.parentStageId
  }

  private static boolean isParentOf(Stage a, Stage b) {
    return a.id == b.parentStageId
  }

  private <T> T fetchWithRetries(Class<T> responseType, Closure<Response> fetchClosure) {
    def converter = new JacksonConverter(mapper)
    final int maxRetries = 5
    final long retryBackoff = 150
    for (int i = 1; i <= maxRetries; i++) {
      try {
        Response response
        try {
          response = fetchClosure.call()
        } catch (RetrofitError re) {
          if (re.kind == RetrofitError.Kind.HTTP && re.response.status == 404) {
            return null
          }
          throw re
        }
        try {
          return (T) converter.fromBody(response.body, responseType)
        } catch (ConversionException ce) {
          throw RetrofitError.conversionError(response.url, response, converter, responseType, ce)
        }
      } catch (RetrofitError re) {
        if (re.kind == RetrofitError.Kind.NETWORK && i < maxRetries) {
          Thread.sleep(retryBackoff)
        } else {
          throw re
        }
      }
    }
  }

}
