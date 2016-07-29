/*******************************************************************************
 * Copyright (C) 2016 Mango Business Solutions Ltd, http://www.mango-solutions.com
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the
 * Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/agpl-3.0.html>.
 *******************************************************************************/
package eu.ddmore.see.tel;

import static java.nio.file.FileVisitResult.CONTINUE;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.lang.StringUtils;
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
    final static Logger LOG = Logger.getLogger(ExecuteTestProjectAT.class);
    private static final String TEST_SCRIPT_NAME_PATTERN = "regex:test\\..*\\.[Rr]";
    private static final String TEST_SCRIPT_WRAPPER_TEMPLATE = "/TestScriptWrapperTemplate.template";
    private static final String TEST_SCRIPT_WRAPPER_FILE_NAME_TEMPLATE = "wrapper.%s";
    private static final String COMMON_PROJECT_MARKER_FILE = ".shared";
    private static final String RDATA_FILE_EXT = "RData";

    private static String tagsInclusionPattern = null;
    private static String tagsExclusionPattern = null;
    
    private final File atWorkingDirectoryParent = new File("t/at").getAbsoluteFile();
    private final File rBinary = new File(System.getProperty("see.home"), System.getProperty("see.RScript"));
    private final File seeHome = new File(System.getProperty("see.home")).getAbsoluteFile();
    private final String buildId = System.getProperty("build.id", "NO_ID");
    private final File cachePath = new File(System.getProperty("cache.path", "t/cache")).getAbsoluteFile();
    private final File testProject;
    private final File testScript;
    /*
    * Attributes controlling test harness behaviour
    */
    static boolean DRY_RUN = true;
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
        TEST_SCRIPT_MODE = TestScriptMode.valueOf(System.getProperty("testScriptMode", TestScriptMode.RunTestScript.name()));
        DRY_RUN = Boolean.parseBoolean(System.getProperty("dryRun", "false"));

        tagsInclusionPattern = System.getProperty("tagsInclusionPattern", ".*");
        tagsExclusionPattern = System.getProperty("tagsExclusionPattern", "");
    }
    /**
     * The method that produces the parameters to be passed to each construction of the test class.
     * In this case, the {@link Path}s that are the test projects and test scripts.
     * 
     * @return paths of projects and test scripts within them, both absolute. 
     */
    @Parameters(name = "{index}: {1} -> {2}")
    public static Iterable<Object[]> getTestProjects() throws Exception {
        setUp();
        File testProjectsLocation = new File(System.getProperty("test.projects")).getAbsoluteFile();

        LOG.debug(String.format("Looking for all files in [%s] matching : [%s]", testProjectsLocation, TEST_SCRIPT_NAME_PATTERN));
        
        File[] testProjects = testProjectsLocation.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return new File(dir, name).isDirectory();
            }
        });
        Preconditions.checkNotNull(testProjects, String.format("No test projects found in %s", testProjectsLocation));
        Map<File, Set<Path>> projectsAndFiles = Maps.newHashMap();
        for(File testProject : testProjects){
            TestScriptFinder testScriptFinder = new TestScriptFinder(TEST_SCRIPT_NAME_PATTERN, tagsInclusionPattern, tagsExclusionPattern);
            LOG.debug(String.format("Test Script include pattern : [%s]", tagsInclusionPattern));
            LOG.debug(String.format("Test Script exclude pattern : [%s]", tagsExclusionPattern));
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
        Collections.sort(parameters, new Comparator<Object[]> () {

            @Override
            public int compare(Object[] left, Object[] right) {
                int projectOrder = ((String)left[1]).compareTo((String)right[1]);
                if(projectOrder!=0) {
                    return projectOrder;
                }
                File lTestScript = getScriptFile(left);
                int lOrder = extractOrder(lTestScript);
                File rTestScript = getScriptFile(right);
                int rOrder = extractOrder(rTestScript);
                return Integer.compare(lOrder, rOrder);
            }

            private File getScriptFile(Object[] element) {
                Path projectPath = (Path)element[0];
                Path testScriptPath = (Path)element[2];
                File testScript = projectPath.resolve(testScriptPath).toFile();
                return testScript;
            }

            private int extractOrder(File testScript) {
                int order = Integer.MAX_VALUE;
                    String value = extractTag(testScript, "Order");
                    if(!StringUtils.isBlank(value)) {
                        order = Integer.parseInt(value);
                    }
                return order;
            }

        });
        return parameters;
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
     * Since:
     * <ol>
     *   <li>there might be interrelations between test projects (e.g. utils script in TestUtils project is sourced by the test scripts)</li>
     *   <li>each Test Script may modify any files</li>
     * </ol>
     * 
     * Test Scripts within the same project share working directory. The working directory imitates MDL IDE workspace and it contains:
     * <ol>
     *   <li>the test project that contains the script being executed</li>
     *   <li>all test projects that contain COMMON_PROJECT_MARKER_FILE marker file</li>
     * </ol>
     * The test is considered successful if it completes without exception.
     */
    @Test
    public void shouldSuccessfulyExecuteTestScript() throws Exception {
        File workingDirectory = prepareWorkspace(testProject, testScript);
        File testScriptPath = new File(new File(workingDirectory, testProject.getName()),testScript.getPath());
        File wrapperScript = new File(testScriptPath.getParentFile(), String.format(TEST_SCRIPT_WRAPPER_FILE_NAME_TEMPLATE,testScript.getName()));
        prepareScriptWrapper(testScriptPath, wrapperScript, workingDirectory);
        LOG.info(StringUtils.repeat("#", 60));
        LOG.info(String.format("Working Directory [%s]",workingDirectory));
        LOG.info(String.format("Test Project [%s]",testProject.getName()));
        LOG.info(String.format("Test Script [%s]",testScriptPath));
        LOG.info(String.format("Wrapper Script [%s]",wrapperScript));
        LOG.info(StringUtils.repeat("#", 60));
        try {
            TestScriptPerformer scriptPerformer = new TestScriptPerformer(wrapperScript, workingDirectory);
            scriptPerformer.setRscriptExecutable(rBinary);
            scriptPerformer.setDryRun(DRY_RUN);
            scriptPerformer.run();
        } finally {
            LOG.info(StringUtils.repeat("#", 60));
            LOG.info(String.format("Test Script Execution [%s] END",testScriptPath));
            LOG.info(StringUtils.repeat("#", 60));
        }
    }

    private File prepareWorkspace(File testProject, File testScript) throws IOException {
        File testWorkingDirectory = generateWorkingDirectoryPath(testProject, testScript);
        if(testWorkingDirectory.exists()) {
            // skip, previous test created the directory structure
            return testWorkingDirectory;
        }
        testWorkingDirectory.mkdirs();
        File[] projectsToCopy = testProject.getParentFile().listFiles((FileFilter)
            FileFilterUtils.and(FileFilterUtils.directoryFileFilter(), 
                FileFilterUtils.or(FileFilterUtils.nameFileFilter(testProject.getName()), new IOFileFilter()  {
            @Override
            public boolean accept(File dir, String name) {
                return false;
            }
            
            @Override
            public boolean accept(File file) {
                if(file.isDirectory()) {
                    return new File(file,COMMON_PROJECT_MARKER_FILE).exists();
                }
                return false;
            }
        })));
        for(File project : projectsToCopy) {
            FileUtils.copyDirectory(project, new File(testWorkingDirectory, project.getName()));
        }
        return testWorkingDirectory;
    }

    private File generateWorkingDirectoryPath(File testProject, File testScript) {
        String name = testProject.getName();
        return new File(atWorkingDirectoryParent, name);
    }

    private void prepareScriptWrapper(File scriptPath, File scriptWrapper, File workingDirectory) throws IOException {
        String template = FileUtils.readFileToString(FileUtils.toFile(ExecuteTestProjectAT.class.getResource(TEST_SCRIPT_WRAPPER_TEMPLATE)));
        template = template
                            .replaceAll("<MDLIDE_WORKSPACE_PATH>",toRPath(workingDirectory))
                            .replaceAll("<SEE_HOME>",toRPath(seeHome))
                            .replaceAll("<TEST_SCRIPT>",generateTestScript(scriptPath))
                            .replaceAll("<PID_FILE>", String.format("%s/%s", toRPath(scriptWrapper.getParentFile()), metaFileName(scriptWrapper.getName(), TestScriptPerformer.PID_FILE_EXT)))
                            .replaceAll("<R_DATA_FILE>", String.format("%s/%s", toRPath(scriptWrapper.getParentFile()), metaFileName(scriptWrapper.getName(), RDATA_FILE_EXT)))
                            .replaceAll("<BUILD_ID>", buildId)
                            .replaceAll("<PROJECT_NAME>", testProject.getName())
                            .replaceAll("<SCRIPT_NAME>", testScript.getName())
                            .replaceAll("<CACHE_DIR>", toRPath(cachePath)
                            );
        FileUtils.writeStringToFile(scriptWrapper, template);
    }

    private static String metaFileName(String baseName, String postfix) {
        return baseName + "." + postfix;
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
        private final Pattern includeMatcher;
        private final Pattern excludeMatcher;
        private final Set<Path> scripts = new HashSet<>();
        TestScriptFinder(String pattern, String tagsInclusionPattern, String tagsExclusionPattern) {
            matcher = FileSystems.getDefault().getPathMatcher(pattern);
            includeMatcher = Pattern.compile(tagsInclusionPattern);
            excludeMatcher = Pattern.compile(tagsExclusionPattern);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (matcher.matches(file.getFileName())) {
                String tags = extractTag(file.toFile(), "Tags");
                LOG.debug(String.format("Test Script's %s tags are:.", file.getFileName(), tags));
                if(StringUtils.isBlank(tags)||(includeMatcher.matcher(tags).matches()&&!excludeMatcher.matcher(tags).matches())) {
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

    private static String extractTag(File file, String tag) {
        Pattern p = Pattern.compile("\\s*#+\\s*"+tag+":\\s*(.+)");
            try {
                List<String> lines = FileUtils.readLines(file);
                for(String line : lines) {
                    Matcher m = p.matcher(line);
                    if(m.matches()) {
                        return m.group(1).trim();
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(String.format("Could not read test script %s.",file), e);
            }
        return "";
    }
}
