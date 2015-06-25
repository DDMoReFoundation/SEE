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
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.TeeOutputStream;
import org.apache.commons.lang.time.StopWatch;
import org.apache.log4j.Logger;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;


/**
 * A Parametrized test case that executes all test scripts named *TestScript.R in MDL IDE test projects. 
 * 
 * The verification and assertions are implemented by the Test Scripts themselves which are expected to result in R 'stop' function call 
 * in case of script's failure.
 * 
 * The test harness ensures that:
 *   * the standard output and error streams from Test Script execution are dumped into files
 *   * the test case fails if the Test Script fails
 *   * R workspace image is dumped to a file
 *   * R working directory is located in the Test Scripts location
 * 
 * This Test Harness supports both - execution from Maven and from IDE but the latter requires that at least Maven's 'pre-integration-test' phase 
 * has been executed first.
 * 
 */
@RunWith(Parameterized.class)
public class ExecuteTestProjectAT {
    private final static Logger LOG = Logger.getLogger(ExecuteTestProjectAT.class);
    private final File testProject;
    private final File testScript;
    private static final String testScriptPattern = "regex:^(?!(test-)).*TestScript\\.[Rr]";
    private File atWorkingDirectoryParent = new File("target/acceptanceTestsWd").getAbsoluteFile();
    private File rBinary = new File(System.getProperty("see.home"), System.getProperty("see.RScript"));
    private File seeHome = new File(System.getProperty("see.home")).getAbsoluteFile();
    
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
    
    
    @BeforeClass
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
    }

    /**
     * Invoked by the {@link Parameterized} runner
     * @param testProject - path of the test project
     * @param testScrip - path of the test script
     */
    public ExecuteTestProjectAT(Path testProject, Path testScript) {
        this.testProject = testProject.toFile().getAbsoluteFile();
        this.testScript = testProject.relativize(testScript).toFile();
    }

    /**
     * The method that produces the parameters to be passed to each construction of the test class.
     * In this case, the {@link Path}s that are the test projects and test scripts.
     * 
     *@return paths of projects and test scripts within them, both absolute. 
     */
    @Parameters(name = "{index}: Test Script {1}")
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
            TestScriptFinder testScriptFinder = new TestScriptFinder(testScriptPattern);
            Files.walkFileTree(testProject.getAbsoluteFile().toPath(), testScriptFinder);
            projectsAndFiles.put(testProject, testScriptFinder.getScripts());
        }
        
        List<Object[]> parameters = Lists.newArrayList();
        for(Entry<File, Set<Path>> en : projectsAndFiles.entrySet()) {
            Path testProject = en.getKey().toPath();
            for(Path testScript : en.getValue()) {
                parameters.add(new Object[] {testProject, testScript});
            }
        }
        return parameters;
    }
    
    /**
     * Since:
     * * there might be interrelations between test projects (e.g. utils script in TestUtils project is sourced by the test scripts)
     * * each Test Script may modify any files
     * 
     * Parent directory of the testScript is treated as MDL IDE workspace and copied to a directory specific to a given Test Script and
     * then the Test Script is executed. This ensures that each Test Script runs in unmodified workspace.
     */
    @Test
    public void shouldSuccessfulyExecuteTestScript() throws Exception {
        File workingDirectory = prepareWorkspace(testProject, testScript);
        File testScriptPath = new File(new File(workingDirectory, testProject.getName()),testScript.getPath());
        File wrapperScript = new File(testScriptPath.getParentFile(), "test-"+testScriptPath.getName());
        prepareScriptWrapper(testScriptPath, wrapperScript, workingDirectory);
        new TestScriptPerformer().run(rBinary, wrapperScript, workingDirectory);
    }

    private File prepareWorkspace(File testProject, File testScript) throws IOException {
        File testWorkingDirectory = new File(atWorkingDirectoryParent, testScript.getName());
        testWorkingDirectory.mkdirs();
        FileUtils.copyDirectory(testProject.getParentFile(), testWorkingDirectory);
        return testWorkingDirectory;
    }

    private void prepareScriptWrapper(File scriptPath, File scriptWrapper, File workingDirectory) throws IOException {
        StringBuilder testScriptWrapper = new StringBuilder();
        testScriptWrapper.append(String.format(".MDLIDE_WORKSPACE_PATH='%s'\n", toRPath(workingDirectory)))
                    .append(String.format("setwd('%s')\n", toRPath(seeHome)))
                    .append("source('ConfigureTelConsole.R')\n")
                    .append("setwd(.MDLIDE_WORKSPACE_PATH)\n")
                    .append(".seeAtResult<- tryCatch({\n")
                    .append(generateTestScript(scriptPath))
                    // 0 - this indicates success
                    .append("\n0\n}, finally = {\n") 
                    // create R workspace image file
                    .append(String.format("\nsave.image(file='%s/%s')\n", toRPath(scriptPath.getParentFile()), scriptWrapper.getName() + ".RData"))
                    // return 100 to indicate failure in case of error
                    .append("\n}, error = function(err) { traceback()\nprint(err)\n return(100)} )\n")
                    //quit, don't create a workspace image and don't use TEL.R's quit but the base implementation, so SEE services are not shut down
                    .append("base::q('no',.seeAtResult,FALSE)"); 
        FileUtils.writeStringToFile(scriptWrapper, testScriptWrapper.toString());
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
        private final Set<Path> scripts = new HashSet<>();
        TestScriptFinder(String pattern) {
            matcher = FileSystems.getDefault().getPathMatcher(pattern);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (matcher.matches(file.getFileName())) {
                scripts.add(file);
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
        private static final String R_TMP_DIRECTORY_SUFIX = ".Rtmp";
        private static final String R_TMP_DIR_ENV_VARIABLE = "TMPDIR";

        public void run(File rscriptExecutable, File scriptPath, File workingDirectory) throws Exception {
            CommandLine cmdLine = new CommandLine(rscriptExecutable);
            cmdLine.addArgument(scriptPath.getPath());
            LOG.debug(String.format("Executing command %s in %s", cmdLine, workingDirectory));
            Map<String,String> env = Maps.newHashMap();
            env.putAll(System.getenv());
            
            DefaultExecutor executor = createCommandExecutor();
            executor.setWorkingDirectory(workingDirectory);
            Path relativeScriptLocation = workingDirectory.toPath().relativize(scriptPath.toPath());
            File tmpDir = new File(workingDirectory, relativeScriptLocation.toString() + R_TMP_DIRECTORY_SUFIX);
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
                    try {
                        if(DRY_RUN) {
                            LOG.debug("Skipping test script execution.");
                        } else {
                            executor.execute(cmdLine, env);
                        }
                    } finally {
                        stopWatch.stop();
                        LOG.info(String.format("Execution of %s script took %s s.", scriptPath,
                        TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime())));
                    }
                }
                
            } catch(Exception e) {
                throw new Exception(String.format("Error when executing %s.\n STDOUT: [%s]\n STDERR: [%s] ", scriptPath, FileUtils.readFileToString(stdoutFile), FileUtils.readFileToString(stderrFile)));
            }
        }
        
        private DefaultExecutor createCommandExecutor() {
            DefaultExecutor executor = new DefaultExecutor();
            executor.setExitValue(0);
            ExecuteWatchdog watchdog = new ExecuteWatchdog(TimeUnit.MINUTES.toMillis(Long.parseLong(System.getProperty("testscript.timeout"))));
            executor.setWatchdog(watchdog);
            return executor;
        }
    }

}
