package org.ddlsmith;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for a defect that only a real multi-threaded run could reveal: the run never ended.
 *
 * <p>The submission loop used to decide inside each worker whether the whole run was over, by
 * incrementing a shared {@code volatile int} and comparing it with the number of databases. Two
 * workers finishing at the same time can lose one of the increments, the counter then never reaches
 * the total, {@code ExecutorService.shutdown()} is never called, and the main thread sits in
 * {@code awaitTermination} forever even though every database has been tested. It reproduced on H2
 * after twenty databases with two threads; the thread dump showed the main thread parked in
 * {@code ThreadPoolExecutor.awaitTermination} while the workers waited for tasks that no longer
 * existed.</p>
 *
 * <p>The run is executed on a separate (daemon) thread so that a regression fails this test with a
 * timeout instead of hanging the build forever.</p>
 */
class RunTerminationTest {

    @Test
    @DisplayName("a multi-threaded run terminates once every database has been tested")
    void multiThreadedRunTerminates() throws Exception {
        ExecutorService caller = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ddlsmith-termination-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Integer> exitCode = caller.submit(() -> Main.executeMain("h2",
                    // enough databases across enough threads that a lost increment is very likely
                    "--num-tries", "32", "--threads", "8", "--random-seed", "4711",
                    // no files: this test is about termination, not about reporting
                    "--bug-dir", "off", "--log-each-select=false", "--print-progress-information=false",
                    "--quiet"));
            assertEquals(0, exitCode.get(180, TimeUnit.SECONDS),
                    "a run with no engine defect must finish successfully instead of blocking");
        } finally {
            caller.shutdownNow();
        }
    }
}
