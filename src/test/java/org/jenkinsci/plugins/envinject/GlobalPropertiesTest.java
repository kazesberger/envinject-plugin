package org.jenkinsci.plugins.envinject;

import hudson.model.FreeStyleBuild;
import hudson.model.Result;
import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.SlaveComputer;
import hudson.tasks.Shell;
import hudson.util.DescribableList;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import junit.framework.Assert;

import org.apache.commons.lang.StringUtils;
import org.junit.Test;
import org.jvnet.hudson.test.HudsonTestCase;

/**
 * @author Gregory Boissinot
 */
public class GlobalPropertiesTest extends HudsonTestCase {


    private FreeStyleProject project;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        project = createFreeStyleProject();
    }

    @Test
    public void testGlobalPropertiesWithWORKSPACE() throws Exception {

        final String testWorkspaceVariableName = "TEST_WORKSPACE";
        final String testWorkspaceVariableValue = "${WORKSPACE}";
        final String workspaceName = "WORKSPACE";

        //A global node property TEST_WORKSPACE
        DescribableList<NodeProperty<?>, NodePropertyDescriptor> globalNodeProperties = Hudson.getInstance().getGlobalNodeProperties();
        globalNodeProperties.add(new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry(testWorkspaceVariableName, testWorkspaceVariableValue)));

        EnvInjectJobProperty jobProperty = new EnvInjectJobProperty();
        jobProperty.setOn(true);
        jobProperty.setKeepBuildVariables(true);
        jobProperty.setKeepJenkinsSystemVariables(true);
        project.addProperty(jobProperty);

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        Assert.assertEquals(Result.SUCCESS, build.getResult());

        org.jenkinsci.lib.envinject.EnvInjectAction action = build.getAction(org.jenkinsci.lib.envinject.EnvInjectAction.class);
        Map<String, String> envVars = action.getEnvMap();

        String workspaceProcessed = envVars.get(workspaceName);
        assertNotNull(workspaceProcessed);
        String testWorkspaceProcessed = envVars.get(testWorkspaceVariableName);
        assertNotNull(testWorkspaceProcessed);

        assertEquals(workspaceProcessed, testWorkspaceProcessed);
    }

    //Specific use case: We set a global workspace at job level
    @Test
    public void testGlobalPropertiesSetWORKSPACE() throws Exception {

        final String testGlobalVariableName = "WORKSPACE";
        final String testGlobalVariableValue = "WORKSPACE_VALUE";
        final String testJobVariableName = "TEST_JOB_WORKSPACE";
        final String testJobVariableExprValue = "${WORKSPACE}";

        DescribableList<NodeProperty<?>, NodePropertyDescriptor> globalNodeProperties = Hudson.getInstance().getGlobalNodeProperties();
        globalNodeProperties.add(new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry(testGlobalVariableName, testGlobalVariableValue)));

        StringBuffer propertiesContent = new StringBuffer();
        propertiesContent.append(testJobVariableName).append("=").append(testJobVariableExprValue);
        EnvInjectJobPropertyInfo info = new EnvInjectJobPropertyInfo(
                null, propertiesContent.toString(), null, null, null, true);
        EnvInjectBuildWrapper envInjectBuildWrapper = new EnvInjectBuildWrapper();
        envInjectBuildWrapper.setInfo(info);
        project.getBuildWrappersList().add(envInjectBuildWrapper);

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        Assert.assertEquals(Result.SUCCESS, build.getResult());

        org.jenkinsci.lib.envinject.EnvInjectAction action = build.getAction(org.jenkinsci.lib.envinject.EnvInjectAction.class);
        Map<String, String> envVars = action.getEnvMap();

        String result_testGlobalVariableName = envVars.get(testGlobalVariableName);
        assertNotNull(result_testGlobalVariableName);
        String result_testJobVariableName = envVars.get(testJobVariableName);
        assertNotNull(result_testJobVariableName);

        assertEquals(result_testGlobalVariableName, result_testJobVariableName);
    }

    @Test
    public void testChangeOfGlobalPropertyGetsRecognizedWhenWithoutJobPropertyAndRunOnSlaves() throws Exception {

        final String testVariableName = "TESTVAR";
        final String testVariableValue = "value1";
        final String testVariableValueAfterChange = "value2";

        //A global node property TESTVAR
        DescribableList<NodeProperty<?>, NodePropertyDescriptor> globalNodeProperties = Hudson.getInstance().getGlobalNodeProperties();
        EnvironmentVariablesNodeProperty.Entry testVarEntry = new EnvironmentVariablesNodeProperty.Entry(testVariableName, testVariableValue);
        EnvironmentVariablesNodeProperty testVarNodePropertyItem = new EnvironmentVariablesNodeProperty(testVarEntry);
        globalNodeProperties.add(testVarNodePropertyItem);

        Node slaveNode = createOnlineSlave();
        project.setAssignedNode(slaveNode);
        project.getBuildersList().add(new Shell("echo \"TESTVAR=$TESTVAR\""));

        // we do NOT add a jobProperty - we want to test "default" behaviour of jenkins with installed envinject plugin
        // so we run the build right away
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        Assert.assertEquals(Result.SUCCESS, build.getResult());
        assertEquals(slaveNode.getNodeName(), build.getBuiltOn().getNodeName());

        // assert correct injection of testVariable #1
        org.jenkinsci.lib.envinject.EnvInjectAction action = build.getAction(org.jenkinsci.lib.envinject.EnvInjectAction.class);
        Map<String, String> envVars = action.getEnvMap();
        String actualTestVariableValueInBuild = envVars.get(testVariableName);
        assertNotNull("actual testVariableValue is null", actualTestVariableValueInBuild);
        assertEquals(testVariableValue, actualTestVariableValueInBuild);

        Set<Entry<String, String>> beforeChange = hudson.getComputer(slaveNode.getNodeName()).getEnvironment().entrySet();

        // now we change the global property variable value...
        testVarEntry = new EnvironmentVariablesNodeProperty.Entry(testVariableName, testVariableValueAfterChange);
        Hudson.getInstance().getGlobalNodeProperties().add(new EnvironmentVariablesNodeProperty(testVarEntry));

        Set<Entry<String, String>> afterChange = hudson.getComputer(slaveNode.getNodeName()).getEnvironment().entrySet();
        // environment of the slave does not change without restarting it. assert it to make test fail if there will be
        // some kind of auto-restart to reload config.
        Assert.assertEquals(beforeChange, afterChange);
        Assert.assertEquals(beforeChange.toString(), afterChange.toString());

        //...run the job again...
        FreeStyleBuild secondBuild = project.scheduleBuild2(0).get();
        Assert.assertEquals(Result.SUCCESS, secondBuild.getResult());
        assertEquals(slaveNode.getNodeName(), secondBuild.getBuiltOn().getNodeName());

        //...and expect the testvariable to have the changed value
        org.jenkinsci.lib.envinject.EnvInjectAction action2 = secondBuild.getAction(org.jenkinsci.lib.envinject.EnvInjectAction.class);
        assertNotNull("actual testVariableValue is null", action2.getEnvMap().get(testVariableName));
        assertEquals(testVariableValueAfterChange, action2.getEnvMap().get(testVariableName));
    }
}
