/*******************************************************************************
 * Copyright (C) 2015 Mango Solutions Ltd - All rights reserved.
 ******************************************************************************/
package eu.ddmore.see.tel;

import static java.nio.file.FileVisitResult.CONTINUE;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.TeeOutputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.StopWatch;
import org.apache.log4j.Logger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;


/**
 * A Parameterized test case that executes all test scripts named *TestScript.R in MDL IDE test projects. 
 * <p>
 * The verification and assertions are implemented by the Test Scripts themselves which are expected to result in R 'stop' function call 
 * in case of script's failure.
 * <p>
 * The test harness ensures that:
 * <ol>
 *   <li>the standard output and error streams from Test Script execution are dumped into files</li>
 *   <li>the test case fails if the Test Script fails</li>
 *   <li>R workspace image is dumped to a file</li>
 *   <li>R working directory is located in the Test Scripts location</li>
 * </ol>
 * This Test Harness supports both - execution from Maven and from IDE but the latter requires that at least Maven's 'pre-integration-test' phase 
 * has been executed first.
 * 
 */
@RunWith(Parameterized.class)
public class ExecuteTestProjectAT {
    private final static Logger LOG = Logger.getLogger(ExecuteTestProjectAT.class);
    private final File testProject;
    private final File testScript;
    private static final String testScriptPattern = "regex:.*TestScript\\.[Rr]";
    private static final String TEST_SCRIPT_WRAPPER_TEMPLATE = "/TestScriptWrapperTemplate.template";
    private File atWorkingDirectoryParent = new File("target/at").getAbsoluteFile();
    private File rBinary = new File(System.getProperty("see.home"), System.getProperty("see.RScript"));
    private File seeHome = new File(System.getProperty("see.home")).getAbsoluteFile();
    private static String includeTestScriptPattern = null;
    private static String excludeTestScriptPattern = null;
    
    /*
    * Attributes controlling test harness behaviour
    */
    private static boolean DRY_RUN = true;
    private static TestScriptMode TEST_SCRIPT_MODE = TestScriptMode.RunTestScript;
    private enum TestScriptMode {
        /**
         * Will source the actual Test Script
         */
        RunTestScript,
        /**
         * Will result in R script that prints useful debug information and failure
         */
        PrintDebugAndFail,
        /**
         * Will result in R script that prints useful debug information and completes successfully
         */
        PrintDebugAndSucceed
    }
    
    public static void setUp() throws Exception {
        Properties properties = new Properties();
        properties.load(ExecuteTestProjectAT.class.getResourceAsStream("/test.properties"));
        for (Entry<Object, Object> en : properties.entrySet()) {
            String key = (String) en.getKey();
            if (System.getProperty(key)==null) {
                System.setProperty(key, (String) en.getValue());
            }
        }
        TEST_SCRIPT_MODE = TestScriptMode.valueOf(System.getProperty("testScriptMode"));
        DRY_RUN = Boolean.parseBoolean(System.getProperty("dryRun"));

        includeTestScriptPattern = System.getProperty("includeTestScriptPattern");
        excludeTestScriptPattern = System.getProperty("excludeTestScriptPattern");
    }

    /**
     * Invoked by the {@link Parameterized} runner
     * @param testProject - path of the test project
     * @param projectName - project name - used just of pretty JUnit test report
     * @param testScrip - path of the test script
     */
    public ExecuteTestProjectAT(Path testProject, String projectName, Path testScript) {
        this.testProject = testProject.toFile().getAbsoluteFile();
        this.testScript = testScript.toFile();
    }

    /**
     * The method that produces the parameters to be passed to each construction of the test class.
     * In this case, the {@link Path}s that are the test projects and test scripts.
     * 
     *@return paths of projects and test scripts within them, both absolute. 
     */
    @Parameters(name = "{index}: Test Project {1}, Test Script {2}")
    public static Iterable<Object[]> getTestProjects() throws Exception {
        setUp();
        File testProjectsLocation = new File(System.getProperty("test.projects")).getAbsoluteFile();

        LOG.debug(String.format("Looking for all files in [%s] matching : [%s]", testProjectsLocation, testScriptPattern));
        
        File[] testProjects = testProjectsLocation.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return new File(dir, name).isDirectory();
            }
        });
        Preconditions.checkNotNull(testProjects, String.format("No test projects found in %s", testProjectsLocation));
        Map<File, Set<Path>> projectsAndFiles = Maps.newHashMap();
        for(File testProject : testProjects){
            TestScriptFinder testScriptFinder = new TestScriptFinder(testScriptPattern, includeTestScriptPattern, excludeTestScriptPattern);
            LOG.debug(String.format("Test Script include pattern : [%s]", includeTestScriptPattern));
            LOG.debug(String.format("Test Script exclude pattern : [%s]", excludeTestScriptPattern));
            Files.walkFileTree(testProject.getAbsoluteFile().toPath(), testScriptFinder);
            projectsAndFiles.put(testProject, testScriptFinder.getScripts());
        }
        
        List<Object[]> parameters = Lists.newArrayList();
        for(Entry<File, Set<Path>> en : projectsAndFiles.entrySet()) {
            Path testProject = en.getKey().toPath();
            for(Path testScript : en.getValue()) {
                Path relativeTestScript = testProject.relativize(testScript);
                parameters.add(new Object[] {testProject, testProject.getFileName().toString(), relativeTestScript});
            }
        }
        return parameters;
    }
    
    /**
     * Since:
     * <ol>
     *   <li>there might be interrelations between test projects (e.g. utils script in TestUtils project is sourced by the test scripts)</li>
     *   <li>each Test Script may modify any files</li>
     * </ol>
     * 
     * Parent directory of the test project is treated as MDL IDE workspace and copied to a directory specific to a given Test Script execution and
     * then the Test Script is executed. This ensures that each Test Script runs in unmodified workspace.
     * 
     * The test is considered successful if it completes without exception.
     */
    @Test
    public void shouldSuccessfulyExecuteTestScript() throws Exception {
        File workingDirectory = prepareWorkspace(testProject, testScript);
        File testScriptPath = new File(new File(workingDirectory, testProject.getName()),testScript.getPath());
        File wrapperScript = new File(testScriptPath.getParentFile(), testFileName("R"));
        prepareScriptWrapper(testScriptPath, wrapperScript, workingDirectory);
        LOG.info(StringUtils.repeat("#", 60));
        LOG.info(String.format("Working Directory [%s]",workingDirectory));
        LOG.info(String.format("Test Project [%s]",testProject.getName()));
        LOG.info(String.format("Test Script [%s]",testScriptPath));
        LOG.info(String.format("Wrapper Script [%s]",wrapperScript));
        LOG.info(StringUtils.repeat("#", 60));
        try {
            new TestScriptPerformer().run(rBinary, wrapperScript, workingDirectory);
        } finally {
            LOG.info(StringUtils.repeat("#", 60));
            LOG.info(String.format("Test Script Execution [%s] END",testScriptPath));
            LOG.info(StringUtils.repeat("#", 60));
        }
    }

    private File prepareWorkspace(File testProject, File testScript) throws IOException {
        File testWorkingDirectory = generateWorkingDirectoryPath(testProject, testScript);
        testWorkingDirectory.mkdirs();
        FileUtils.copyDirectory(testProject.getParentFile(), testWorkingDirectory);
        return testWorkingDirectory;
    }

    private File generateWorkingDirectoryPath(File testProject, File testScript) {
        String name = testProject.getName() + "_" + testScript.getName().substring(0, (testScript.getName().length()>20)?20:testScript.getName().length());
        return new File(atWorkingDirectoryParent, name);
    }

    private void prepareScriptWrapper(File scriptPath, File scriptWrapper, File workingDirectory) throws IOException {
        String template = FileUtils.readFileToString(FileUtils.toFile(ExecuteTestProjectAT.class.getResource(TEST_SCRIPT_WRAPPER_TEMPLATE)));
        template = template
        .replaceAll("<MDLIDE_WORKSPACE_PATH>",toRPath(workingDirectory))
        .replaceAll("<SEE_HOME>",toRPath(seeHome))
        .replaceAll("<TEST_SCRIPT>",generateTestScript(scriptPath))
        .replaceAll("<R_DATA_FILE>", String.format("%s/%s", toRPath(scriptPath.getParentFile()), testFileName("RData")));
        FileUtils.writeStringToFile(scriptWrapper, template);
    }

    private static String testFileName(String extension) {
        return "test." + extension;
    }

    private String generateTestScript(File scriptPath) {
        String debugInfo = String.format("print(paste0('Script that would be executed:','%s'))\nprint(paste0('Temp dir is:',tempdir()))\n",toRPath(scriptPath));
        switch(TEST_SCRIPT_MODE) {
            case RunTestScript:
                return String.format("source('%s')\n",toRPath(scriptPath));
            case PrintDebugAndFail:
                return debugInfo + "stop('This is failure')";
            case PrintDebugAndSucceed:
                return debugInfo;
        }
        throw new IllegalStateException("Unsupported test script mode has been selected");
    }

    private String toRPath(File path) {
        return path.getAbsolutePath().replaceAll("\\\\", "/");
    }
    
    /**
     * A File visitor that is used to identify R test scripts.
     */
    private static class TestScriptFinder extends SimpleFileVisitor<Path> {
        private final PathMatcher matcher;
        private final PathMatcher includeMatcher;
        private final PathMatcher excludeMatcher;
        private final Set<Path> scripts = new HashSet<>();
        TestScriptFinder(String pattern, String includeTestScriptPattern, String excludeTestScriptPattern) {
            matcher = FileSystems.getDefault().getPathMatcher(pattern);
            includeMatcher = FileSystems.getDefault().getPathMatcher(includeTestScriptPattern);
            excludeMatcher = FileSystems.getDefault().getPathMatcher(excludeTestScriptPattern);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (matcher.matches(file.getFileName())) {
                if(includeMatcher.matches(file.getFileName())&&!excludeMatcher.matches(file.getFileName())) {
                    LOG.debug(String.format("Found test script %s.", file.getFileName()));
                    scripts.add(file);
                } else {
                    LOG.trace(String.format("Path %s was explicitly ignored by include/exclude mechanism.", file.getFileName(), matcher));
                }
            } else {
                LOG.trace(String.format("Path %s didn't match %s", file.getFileName(), matcher));
            }
            return CONTINUE;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            throw new RuntimeException(String.format("Failed to visit file %s.", file), exc);
        }
        
        public Set<Path> getScripts() {
            return scripts;
        }
    }

    /**
     * Instances of this class are responsible for executing given R script in a given working directory in Rscript.exe binary
     */
    private static class TestScriptPerformer {
        private static final String STDOUT = ".stdout";
        private static final String STDERR = ".stderr";
        private static final String R_TMP_DIRECTORY_SUFFIX = ".Rtmp";
        private static final String R_TMP_DIR_ENV_VARIABLE = "TMPDIR";
        private static Long PROCESS_TIMEOUT = TimeUnit.MINUTES.toMillis(Long.parseLong(System.getProperty("testscript.timeout")));

        public void run(File rscriptExecutable, File scriptPath, File workingDirectory) throws Exception {
            CommandLine cmdLine = new CommandLine(rscriptExecutable);
            cmdLine.addArgument(scriptPath.getPath());
            LOG.debug(String.format("Executing command %s in %s", cmdLine, workingDirectory));
            Map<String,String> env = Maps.newHashMap();
            env.putAll(System.getenv());
            
            DefaultExecutor executor = createCommandExecutor();
            executor.setWorkingDirectory(workingDirectory);
            Path relativeScriptLocation = workingDirectory.toPath().relativize(scriptPath.toPath());
            File tmpDir = new File(workingDirectory, relativeScriptLocation.toString() + R_TMP_DIRECTORY_SUFFIX);
            if(!tmpDir.exists()) {
                Preconditions.checkState(tmpDir.mkdirs(), String.format("Could not create R tmp directory %s.", tmpDir));
            }
            env.put(R_TMP_DIR_ENV_VARIABLE, tmpDir.getAbsolutePath());
            File stdoutFile = new File(workingDirectory, relativeScriptLocation.toString() + STDOUT);
            File stderrFile = new File(workingDirectory, relativeScriptLocation.toString()  + STDERR);
            try {
                try (BufferedOutputStream stdoutOS = new BufferedOutputStream(new TeeOutputStream(new FileOutputStream(stdoutFile),System.out));
                        BufferedOutputStream stderrOS = new BufferedOutputStream(new TeeOutputStream(new FileOutputStream(stderrFile),System.err))) {
                    PumpStreamHandler pumpStreamHandler = new PumpStreamHandler(stdoutOS, stderrOS);
                    executor.setStreamHandler(pumpStreamHandler);
                    StopWatch stopWatch = new StopWatch();
                    stopWatch.start();
                    DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();
                    try {
                        if(DRY_RUN) {
                            LOG.debug("Skipping test script execution.");
                        } else {
                            executor.execute(cmdLine, env, resultHandler);
                            monitorProgress(executor, resultHandler);
                        }
                    } finally {
                        stopWatch.stop();
                        LOG.info(String.format("Execution of %s script took %s s.", scriptPath,
                        TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime())));
                    }
                }
                
            } catch(Exception e) {
                throw new Exception(String.format("Error when executing %s.\n STDOUT: [%s]\n STDERR: [%s] ", scriptPath, FileUtils.readFileToString(stdoutFile), FileUtils.readFileToString(stderrFile)),e);
            }
        }
        /**
         * This method performs monitoring of the external process. Since WatchDog can't be relied on this method actively polls
         * for running process and enforces process destruction if timeout is reached.
         * This approach is even suggested by https://commons.apache.org/proper/commons-exec/apidocs/org/apache/commons/exec/ExecuteWatchdog.html
         * @param externalProcessInput 
         */
        private void monitorProgress(DefaultExecutor executor, DefaultExecuteResultHandler resultHandler) throws Exception {
            boolean monitor = true;
            long waitedSoFar = 0l;
            long step = TimeUnit.SECONDS.toMillis(20);
            while(monitor) {
                waitedSoFar+=step;
                resultHandler.waitFor(step);
                if(resultHandler.hasResult()) {
                    break;
                }
                if(waitedSoFar>PROCESS_TIMEOUT) {
                    LOG.error("Attempting to destroy external process...");
                    /* We invoke destroyProcess method, but this is not guaranteed to work and sometimes results in a detachment from the external process.
                     * There is also no easy way of sending SIGINT signal to an external process on Windows platform hence we can't guarantee that
                     * the external process actually stops.
                     * 
                     * Here we can only give some time to an external process to shut down. We accept this resource leak just because
                     * in this particular case even if the Rscript being run was killed underlying NONMEM execution would still run anyway
                     * (since DDMoRe connectors do not support cancellation).
                     */
                    executor.getWatchdog().destroyProcess();
                    if(waitedSoFar>(PROCESS_TIMEOUT+step)) {
                        monitor = false;
                        LOG.error("The external process did not stop.");
                    }
                }
            }
            if(executor.getWatchdog().killedProcess()) {
                throw new IllegalStateException("The process timed out.");
            } else {
                if(executor.isFailure(resultHandler.getExitValue())) {
                    throw new Exception("External process exited with non-zero exit value.");
                }
            }
        }
        
        private DefaultExecutor createCommandExecutor() {
            DefaultExecutor executor = new DefaultExecutor();
            executor.setExitValue(0);
            ExecuteWatchdog watchdog = new ExecuteWatchdog(ExecuteWatchdog.INFINITE_TIMEOUT);
            executor.setWatchdog(watchdog);
            return executor;
        }
    }

}
