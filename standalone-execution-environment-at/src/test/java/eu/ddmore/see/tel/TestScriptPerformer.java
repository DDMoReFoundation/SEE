/*******************************************************************************
 * Copyright (C) 2015 Mango Solutions Ltd - All rights reserved.
 ******************************************************************************/
package eu.ddmore.see.tel;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
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

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

/**
 * Instances of this class are responsible for executing given R script in a given working directory with Rscript.exe
 */
class TestScriptPerformer {

    private static final Logger LOG = Logger.getLogger(TestScriptPerformer.class);
    private static final String STDOUT_FILE_EXT = "stdout";
    private static final String STDERR_FILE_EXT = "stderr";
    static final String PID_FILE_EXT = "PID";
    private static final String R_TMP_DIRECTORY_SUFFIX = ".Rtmp";
    private static final String R_TMP_DIR_ENV_VARIABLE = "TMPDIR";
    private static final String OUTPUT_SEPARATOR_LINE = StringUtils.repeat("=", 80);
    private static Long PROCESS_TIMEOUT = TimeUnit.MINUTES.toMillis(Long.parseLong(System.getProperty("testscript.timeout")));
    private boolean dryRun;
    private File workingDirectory;
    private File scriptPath;
    private File rscriptExecutable;

    TestScriptPerformer(File scriptPath, File workingDirectory) {
        this.workingDirectory = workingDirectory;
        this.scriptPath = scriptPath;
    }

    public void run() throws Exception {
        CommandLine cmdLine = new CommandLine(rscriptExecutable);
        cmdLine.addArgument(scriptPath.getPath());
        LOG.debug(String.format("Executing command %s in %s", cmdLine, workingDirectory));
        Map<String, String> env = Maps.newHashMap();
        env.putAll(System.getenv());

        DefaultExecutor executor = createCommandExecutor();
        executor.setWorkingDirectory(workingDirectory);
        Path relativeScriptLocation = workingDirectory.toPath().relativize(scriptPath.toPath());
        File tmpDir = new File(workingDirectory, relativeScriptLocation.toString() + R_TMP_DIRECTORY_SUFFIX);
        if (!tmpDir.exists()) {
            Preconditions.checkState(tmpDir.mkdirs(), String.format("Could not create R tmp directory %s.", tmpDir));
        }
        env.put(R_TMP_DIR_ENV_VARIABLE, tmpDir.getAbsolutePath());
        File stdoutFile = new File(workingDirectory, relativeScriptLocation.toString() + "." + STDOUT_FILE_EXT);
        File stderrFile = new File(workingDirectory, relativeScriptLocation.toString() + "." + STDERR_FILE_EXT);
        File pidFile = new File(workingDirectory, relativeScriptLocation.toString() + "." + PID_FILE_EXT);
        try {
            try (BufferedOutputStream stdoutOS = new BufferedOutputStream(new TeeOutputStream(new FileOutputStream(stdoutFile), System.out));
                    BufferedOutputStream stderrOS = new BufferedOutputStream(new TeeOutputStream(new FileOutputStream(stderrFile),
                            System.err))) {
                PumpStreamHandler pumpStreamHandler = new PumpStreamHandler(stdoutOS, stderrOS);
                executor.setStreamHandler(pumpStreamHandler);
                StopWatch stopWatch = new StopWatch();
                stopWatch.start();
                DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();
                try {
                    if (isDryRun()) {
                        LOG.debug("Skipping test script execution.");
                    } else {
                        executor.execute(cmdLine, env, resultHandler);
                        monitorProgress(executor, resultHandler, pidFile);
                    }
                } finally {
                    stopWatch.stop();
                    LOG.info(String.format("Execution of %s script took %s s.", scriptPath,
                        TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime())));
                }
            }

        } catch (Exception e) {
            throw new Exception(String.format("Error when executing %s.\n STDOUT: [%s]\n[%s]\n[%s]\n STDERR: [%s]\n[%s]\n[%s]\n ", scriptPath,
                OUTPUT_SEPARATOR_LINE, FileUtils.readFileToString(stdoutFile), OUTPUT_SEPARATOR_LINE, OUTPUT_SEPARATOR_LINE, FileUtils.readFileToString(stderrFile), OUTPUT_SEPARATOR_LINE), e);
        }
    }

    /**
     * This method performs monitoring of the external process. Since WatchDog can't be relied on this method actively polls
     * for running process and enforces process destruction if timeout is reached.
     * This approach is even suggested by https://commons.apache.org/proper/commons-exec/apidocs/org/apache/commons/exec/ExecuteWatchdog.html
     * @param pidFile 
     * @param externalProcessInput 
     */
    private void monitorProgress(DefaultExecutor executor, DefaultExecuteResultHandler resultHandler, File pidFile) throws Exception {
        boolean monitor = true;
        long waitedSoFar = 0l;
        long step = TimeUnit.SECONDS.toMillis(20);
        while (monitor) {
            waitedSoFar += step;
            resultHandler.waitFor(step);
            if (resultHandler.hasResult()) {
                break;
            }
            if (waitedSoFar > PROCESS_TIMEOUT) {
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
                if (waitedSoFar > (PROCESS_TIMEOUT + step)) {
                    monitor = false;
                    LOG.error("The external process did not stop using Commons Exec.");
                    killProcess(pidFile);
                }
            }
        }
        if (executor.getWatchdog().killedProcess()) {
            throw new IllegalStateException("The process timed out.");
        } else {
            if (executor.isFailure(resultHandler.getExitValue())) {
                throw new Exception("External process exited with non-zero exit value.");
            }
        }
    }

    private void killProcess(File pidFile) {
        String pid;
        try {
            pid = FileUtils.readFileToString(pidFile);
        } catch (IOException e1) {
            throw new RuntimeException(String.format("Could not read process PID file %s.", pidFile));
        }
        String killCommand = String.format("powershell Stop-Process %s", pid);
        LOG.info(String.format("Attempting to kill process using command [%s].", killCommand));
        try {
            DefaultExecutor executor = createCommandExecutor();
            int exitCode = executor.execute(CommandLine.parse(killCommand));
            LOG.info(String.format("Kill command returned %s", exitCode));
        } catch (Exception e) {
            throw new RuntimeException(String.format("Could not stop process with PID [%s].", pid));
        }

    }

    private DefaultExecutor createCommandExecutor() {
        DefaultExecutor executor = new DefaultExecutor();
        executor.setExitValue(0);
        ExecuteWatchdog watchdog = new ExecuteWatchdog(ExecuteWatchdog.INFINITE_TIMEOUT);
        executor.setWatchdog(watchdog);
        return executor;
    }

    public void setRscriptExecutable(File rscriptExecutable) {
        this.rscriptExecutable = rscriptExecutable;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

}