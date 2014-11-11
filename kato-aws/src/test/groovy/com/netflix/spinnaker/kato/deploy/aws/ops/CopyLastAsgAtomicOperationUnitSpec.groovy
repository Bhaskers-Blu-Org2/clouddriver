/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.netflix.spinnaker.kato.deploy.aws.ops
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model.*
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult
import com.amazonaws.services.ec2.model.SecurityGroup
import com.netflix.amazoncomponents.security.AmazonClientProvider
import com.netflix.spinnaker.amos.aws.NetflixAssumeRoleAmazonCredentials
import com.netflix.spinnaker.kato.config.AmazonBlockDevice
import com.netflix.spinnaker.kato.data.task.Task
import com.netflix.spinnaker.kato.data.task.TaskRepository
import com.netflix.spinnaker.kato.deploy.aws.AmazonDeploymentResult
import com.netflix.spinnaker.kato.deploy.aws.AsgReferenceCopier
import com.netflix.spinnaker.kato.deploy.aws.description.BasicAmazonDeployDescription
import com.netflix.spinnaker.kato.deploy.aws.handlers.BasicAmazonDeployHandler
import com.netflix.spinnaker.kato.services.RegionScopedProviderFactory
import spock.lang.Specification

class CopyLastAsgAtomicOperationUnitSpec extends Specification {

  def setupSpec() {
    TaskRepository.threadLocalTask.set(Mock(Task))
  }

  void "operation builds description based on ancestor asg"() {
    setup:
    def deployHandler = Mock(BasicAmazonDeployHandler)
    def description = new BasicAmazonDeployDescription(application: "asgard", stack: "stack")
    description.availabilityZones = ['us-east-1': [], 'us-west-1': []]
    description.credentials = new NetflixAssumeRoleAmazonCredentials(name: "baz")
    description.securityGroups = ['someGroupName', 'sg-12345a']
    description.capacity = new BasicAmazonDeployDescription.Capacity(min: 1, max: 3, desired: 5)
    def mockEC2 = Mock(AmazonEC2)
    def mockAutoScaling = Mock(AmazonAutoScaling)
    def mockProvider = Mock(AmazonClientProvider)
    mockProvider.getAmazonEC2(_, _) >> mockEC2
    mockProvider.getAutoScaling(_, _) >> mockAutoScaling
    def op = new CopyLastAsgAtomicOperation(description)
    op.amazonClientProvider = mockProvider
    op.basicAmazonDeployHandler = deployHandler
    def mockAsgReferenceCopier = Mock(AsgReferenceCopier)
    op.regionScopedProviderFactory = Stub(RegionScopedProviderFactory) {
      forRegion(_, _) >> Stub(RegionScopedProviderFactory.RegionScopedProvider) {
        getAsgReferenceCopier(_, _) >> mockAsgReferenceCopier
      }
    }
    def expectedDeployDescription = { region ->
      new BasicAmazonDeployDescription(application: 'asgard', stack: 'stack', keyPair: 'key-pair-name',
        blockDevices: [new AmazonBlockDevice(deviceName: '/dev/sdb', size: 125), new AmazonBlockDevice(deviceName: '/dev/sdc', virtualName: 'ephemeral1')],
        securityGroups: ['someGroupName', 'otherGroupName'], availabilityZones: [(region): null],
        capacity: new BasicAmazonDeployDescription.Capacity(min: 1, max: 3, desired: 5),
        source: new BasicAmazonDeployDescription.Source())
    }

    when:
    op.operate([])

    then:
    2 * mockEC2.describeSecurityGroups(_) >> { DescribeSecurityGroupsRequest request ->
      assert request.groupNames == ['someGroupName']
      assert request.groupIds == ['sg-12345a']
      def grpByName = Mock(SecurityGroup)
      grpByName.getGroupName() >> "someGroupName"
      grpByName.getGroupId() >> "sg-12345b"
      def grpById = Mock(SecurityGroup)
      grpById.getGroupName() >> "otherGroupName"
      grpById.getGroupId() >> "sg-12345a"
      new DescribeSecurityGroupsResult().withSecurityGroups([grpByName, grpById])
    }
    2 * mockAutoScaling.describeLaunchConfigurations(_) >> { DescribeLaunchConfigurationsRequest request ->
      assert request.launchConfigurationNames == ['foo']
      def mockLaunch = Mock(LaunchConfiguration)
      mockLaunch.getLaunchConfigurationName() >> "foo"
      mockLaunch.getKeyName() >> "key-pair-name"
      mockLaunch.getBlockDeviceMappings() >> [new BlockDeviceMapping().withDeviceName('/dev/sdb').withEbs(new Ebs().withVolumeSize(125)), new BlockDeviceMapping().withDeviceName('/dev/sdc').withVirtualName('ephemeral1')]
      new DescribeLaunchConfigurationsResult().withLaunchConfigurations([mockLaunch])
    }
    2 * mockAutoScaling.describeAutoScalingGroups(_) >> {
      def mockAsg = Mock(AutoScalingGroup)
      mockAsg.getAutoScalingGroupName() >> "asgard-stack-v000"
      mockAsg.getMinSize() >> 0
      mockAsg.getMaxSize() >> 2
      mockAsg.getDesiredCapacity() >> 4
      mockAsg.getLaunchConfigurationName() >> "foo"
      new DescribeAutoScalingGroupsResult().withAutoScalingGroups([mockAsg])
    }
    1 * deployHandler.handle(expectedDeployDescription('us-east-1'), _) >> new AmazonDeploymentResult(asgNameByRegion: ['us-east-1': 'asgard-stack-v001'])
    1 * deployHandler.handle(expectedDeployDescription('us-west-1'), _) >> new AmazonDeploymentResult(asgNameByRegion: ['us-west-1': 'asgard-stack-v001'])
    with(mockAsgReferenceCopier) {
      2 * copyScalingPoliciesWithAlarms('asgard-stack-v000', 'asgard-stack-v001')
      2 * copyScheduledActionsForAsg('asgard-stack-v000', 'asgard-stack-v001')
    }
  }
}