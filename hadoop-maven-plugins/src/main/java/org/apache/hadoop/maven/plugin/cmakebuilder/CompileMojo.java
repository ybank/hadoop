/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.maven.plugin.cmakebuilder;

import java.util.Locale;
import org.apache.hadoop.maven.plugin.util.Exec.OutputBufferThread;
import org.apache.hadoop.maven.plugin.util.Exec;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Goal which builds the native sources.
 */
@Mojo(name="cmake-compile", defaultPhase = LifecyclePhase.COMPILE)
public class CompileMojo extends AbstractMojo {
  private static int availableProcessors =
      Runtime.getRuntime().availableProcessors();

  /**
   * Location of the build products.
   */
  @Parameter(defaultValue="${project.build.directory}/native")
  private File output;

  /**
   * Location of the source files.
   * This should be where the sources are checked in.
   */
  @Parameter(defaultValue="${basedir}/src/main/native", required=true)
  private File source;

  /**
   * CMake build target.
   */
  @Parameter
  private String target;

  /**
   * Environment variables to pass to CMake.
   *
   * Note that it is usually better to use a CMake variable than an environment
   * variable.  To quote the CMake FAQ:
   *
   * "One should avoid using environment variables for controlling the flow of
   * CMake code (such as in IF commands). The build system generated by CMake
   * may re-run CMake automatically when CMakeLists.txt files change. The
   * environment in which this is executed is controlled by the build system and
   * may not match that in which CMake was originally run. If you want to
   * control build settings on the CMake command line, you need to use cache
   * variables set with the -D option. The settings will be saved in
   * CMakeCache.txt so that they don't have to be repeated every time CMake is
   * run on the same build tree."
   */
  @Parameter
  private Map<String, String> env;

  /**
   * CMake cached variables to set.
   */
  @Parameter
  private Map<String, String> vars;

  // TODO: support Windows
  private static void validatePlatform() throws MojoExecutionException {
    if (System.getProperty("os.name").toLowerCase(Locale.ENGLISH)
        .startsWith("windows")) {
      throw new MojoExecutionException("CMakeBuilder does not yet support " +
          "the Windows platform.");
    }
  }

  public void execute() throws MojoExecutionException {
    long start = System.nanoTime();
    validatePlatform();
    runCMake();
    runMake();
    runMake(); // The second make is a workaround for HADOOP-9215.  It can be
               // removed when cmake 2.6 is no longer supported.
    long end = System.nanoTime();
    getLog().info("cmake compilation finished successfully in " +
          TimeUnit.MILLISECONDS.convert(end - start, TimeUnit.NANOSECONDS) +
          " millisecond(s).");
  }

  /**
   * Validate that source parameters look sane.
   */
  static void validateSourceParams(File source, File output)
      throws MojoExecutionException {
    String cOutput = null, cSource = null;
    try {
      cOutput = output.getCanonicalPath();
    } catch (IOException e) {
      throw new MojoExecutionException("error getting canonical path " +
          "for output", e);
    }
    try {
      cSource = source.getCanonicalPath();
    } catch (IOException e) {
      throw new MojoExecutionException("error getting canonical path " +
          "for source", e);
    }

    // This doesn't catch all the bad cases-- we could be following symlinks or
    // hardlinks, etc.  However, this will usually catch a common mistake.
    if (cSource.startsWith(cOutput)) {
      throw new MojoExecutionException("The source directory must not be " +
          "inside the output directory (it would be destroyed by " +
          "'mvn clean')");
    }
  }

  public void runCMake() throws MojoExecutionException {
    validatePlatform();
    validateSourceParams(source, output);

    if (output.mkdirs()) {
      getLog().info("mkdirs '" + output + "'");
    }
    List<String> cmd = new LinkedList<String>();
    cmd.add("cmake");
    cmd.add(source.getAbsolutePath());
    for (Map.Entry<String, String> entry : vars.entrySet()) {
      if ((entry.getValue() != null) && (!entry.getValue().equals(""))) {
        cmd.add("-D" + entry.getKey() + "=" + entry.getValue());
      }
    }
    cmd.add("-G");
    cmd.add("Unix Makefiles");
    String prefix = "";
    StringBuilder bld = new StringBuilder();
    for (String c : cmd) {
      bld.append(prefix).append(c);
      prefix = " ";
    }
    getLog().info("Running " + bld.toString());
    getLog().info("with extra environment variables " + Exec.envToString(env));
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(output);
    pb.redirectErrorStream(true);
    Exec.addEnvironment(pb, env);
    Process proc = null;
    OutputBufferThread outThread = null;
    int retCode = -1;
    try {
      proc = pb.start();
      outThread = new OutputBufferThread(proc.getInputStream());
      outThread.start();

      retCode = proc.waitFor();
      if (retCode != 0) {
        throw new MojoExecutionException("CMake failed with error code " +
            retCode);
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Error executing CMake", e);
    } catch (InterruptedException e) {
      throw new MojoExecutionException("Interrupted while waiting for " +
          "CMake process", e);
    } finally {
      if (proc != null) {
        proc.destroy();
      }
      if (outThread != null) {
        try {
          outThread.interrupt();
          outThread.join();
        } catch (InterruptedException e) {
          getLog().error("Interrupted while joining output thread", e);
        }
        if (retCode != 0) {
          for (String line : outThread.getOutput()) {
            getLog().warn(line);
          }
        }
      }
    }
  }

  public void runMake() throws MojoExecutionException {
    List<String> cmd = new LinkedList<String>();
    cmd.add("make");
    cmd.add("-j");
    cmd.add(String.valueOf(availableProcessors));
    cmd.add("VERBOSE=1");
    if (target != null) {
      cmd.add(target);
    }
    StringBuilder bld = new StringBuilder();
    String prefix = "";
    for (String c : cmd) {
      bld.append(prefix).append(c);
      prefix = " ";
    }
    getLog().info("Running " + bld.toString());
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(output);
    Process proc = null;
    int retCode = -1;
    OutputBufferThread stdoutThread = null, stderrThread = null;
    try {
      proc = pb.start();
      stdoutThread = new OutputBufferThread(proc.getInputStream());
      stderrThread = new OutputBufferThread(proc.getErrorStream());
      stdoutThread.start();
      stderrThread.start();
      retCode = proc.waitFor();
      if (retCode != 0) {
        throw new MojoExecutionException("make failed with error code " +
            retCode);
      }
    } catch (InterruptedException e) {
      throw new MojoExecutionException("Interrupted during Process#waitFor", e);
    } catch (IOException e) {
      throw new MojoExecutionException("Error executing make", e);
    } finally {
      if (stdoutThread != null) {
        try {
          stdoutThread.join();
        } catch (InterruptedException e) {
          getLog().error("Interrupted while joining stdoutThread", e);
        }
        if (retCode != 0) {
          for (String line: stdoutThread.getOutput()) {
            getLog().warn(line);
          }
        }
      }
      if (stderrThread != null) {
        try {
          stderrThread.join();
        } catch (InterruptedException e) {
          getLog().error("Interrupted while joining stderrThread", e);
        }
        // We always print stderr, since it contains the compiler warning
        // messages.  These are interesting even if compilation succeeded.
        for (String line: stderrThread.getOutput()) {
          getLog().warn(line);
        }
      }
      if (proc != null) {
        proc.destroy();
      }
    }
  }
}
