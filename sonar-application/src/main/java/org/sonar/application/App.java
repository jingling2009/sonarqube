/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.application;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.process.Lifecycle;
import org.sonar.process.ProcessId;
import org.sonar.process.ProcessProperties;
import org.sonar.process.Props;
import org.sonar.process.Stoppable;
import org.sonar.process.monitor.JavaCommand;
import org.sonar.process.monitor.Monitor;

import static org.sonar.process.Lifecycle.State;
import static org.sonar.process.ProcessId.APP;
import static org.sonar.process.ProcessProperties.HTTPS_PROXY_HOST;
import static org.sonar.process.ProcessProperties.HTTPS_PROXY_PORT;
import static org.sonar.process.ProcessProperties.HTTP_PROXY_HOST;
import static org.sonar.process.ProcessProperties.HTTP_PROXY_PORT;

/**
 * Entry-point of process that starts and monitors ElasticSearch, the Web Server and the Compute Engine.
 */
public class App implements Stoppable {

  /**
   * Properties about proxy that must be set as system properties
   */
  private static final String[] PROXY_PROPERTY_KEYS = new String[] {
    HTTP_PROXY_HOST,
    HTTP_PROXY_PORT,
    "http.nonProxyHosts",
    HTTPS_PROXY_HOST,
    HTTPS_PROXY_PORT,
    "http.auth.ntlm.domain",
    "socksProxyHost",
    "socksProxyPort"};

  private final Monitor monitor;

  public App(AppFileSystem appFileSystem, boolean watchForHardStop) {
    this(Monitor.create(APP.getIpcIndex(), appFileSystem, watchForHardStop, new AppLifecycleListener()));
  }

  App(Monitor monitor) {
    this.monitor = monitor;
  }

  public void start(Props props) throws InterruptedException {
    monitor.start(createCommands(props));
    monitor.awaitTermination();
  }

  private static List<JavaCommand> createCommands(Props props) {
    File homeDir = props.nonNullValueAsFile(ProcessProperties.PATH_HOME);
    List<JavaCommand> commands = new ArrayList<>(3);
    if (isProcessEnabled(props, ProcessProperties.CLUSTER_SEARCH_DISABLED)) {
      commands.add(createESCommand(props, homeDir));
    }

    if (isProcessEnabled(props, ProcessProperties.CLUSTER_WEB_DISABLED)) {
      commands.add(createWebServerCommand(props, homeDir));
    }

    if (isProcessEnabled(props, ProcessProperties.CLUSTER_CE_DISABLED)) {
      commands.add(createCeServerCommand(props, homeDir));
    }

    return commands;
  }

  private static boolean isProcessEnabled(Props props, String disabledPropertyKey) {
    return !props.valueAsBoolean(ProcessProperties.CLUSTER_ENABLED) ||
      !props.valueAsBoolean(disabledPropertyKey);
  }

  private static JavaCommand createESCommand(Props props, File homeDir) {
    return newJavaCommand(ProcessId.ELASTICSEARCH, props, homeDir)
      .addJavaOptions("-Djava.awt.headless=true")
      .addJavaOptions(props.nonNullValue(ProcessProperties.SEARCH_JAVA_OPTS))
      .addJavaOptions(props.nonNullValue(ProcessProperties.SEARCH_JAVA_ADDITIONAL_OPTS))
      .setClassName("org.sonar.search.SearchServer")
      .addClasspath("./lib/common/*")
      .addClasspath("./lib/search/*");
  }

  private static JavaCommand createWebServerCommand(Props props, File homeDir) {
    JavaCommand command = newJavaCommand(ProcessId.WEB_SERVER, props, homeDir)
      .addJavaOptions(ProcessProperties.WEB_ENFORCED_JVM_ARGS)
      .addJavaOptions(props.nonNullValue(ProcessProperties.WEB_JAVA_OPTS))
      .addJavaOptions(props.nonNullValue(ProcessProperties.WEB_JAVA_ADDITIONAL_OPTS))
      // required for logback tomcat valve
      .setEnvVariable(ProcessProperties.PATH_LOGS, props.nonNullValue(ProcessProperties.PATH_LOGS))
      // ensure JRuby uses SQ's temp directory as temp directory (eg. for temp files used during HTTP uploads)
      .setEnvVariable("TMPDIR", props.nonNullValue(ProcessProperties.PATH_TEMP))
      .setClassName("org.sonar.server.app.WebServer")
      .addClasspath("./lib/common/*")
      .addClasspath("./lib/server/*");
    String driverPath = props.value(ProcessProperties.JDBC_DRIVER_PATH);
    if (driverPath != null) {
      command.addClasspath(driverPath);
    }
    return command;
  }

  private static JavaCommand createCeServerCommand(Props props, File homeDir) {
    JavaCommand command = newJavaCommand(ProcessId.COMPUTE_ENGINE, props, homeDir)
      .addJavaOptions(ProcessProperties.CE_ENFORCED_JVM_ARGS)
      .addJavaOptions(props.nonNullValue(ProcessProperties.CE_JAVA_OPTS))
      .addJavaOptions(props.nonNullValue(ProcessProperties.CE_JAVA_ADDITIONAL_OPTS))
      .setClassName("org.sonar.ce.app.CeServer")
      .addClasspath("./lib/common/*")
      .addClasspath("./lib/server/*")
      .addClasspath("./lib/ce/*");
    String driverPath = props.value(ProcessProperties.JDBC_DRIVER_PATH);
    if (driverPath != null) {
      command.addClasspath(driverPath);
    }
    return command;
  }

  private static JavaCommand newJavaCommand(ProcessId id, Props props, File homeDir) {
    JavaCommand command = new JavaCommand(id)
      .setWorkDir(homeDir)
      .setArguments(props.rawProperties());

    for (String key : PROXY_PROPERTY_KEYS) {
      if (props.contains(key)) {
        command.addJavaOption("-D" + key + "=" + props.value(key));
      }
    }
    // defaults of HTTPS are the same than HTTP defaults
    setSystemPropertyToDefaultIfNotSet(command, props, HTTPS_PROXY_HOST, HTTP_PROXY_HOST);
    setSystemPropertyToDefaultIfNotSet(command, props, HTTPS_PROXY_PORT, HTTP_PROXY_PORT);
    return command;
  }

  private static void setSystemPropertyToDefaultIfNotSet(JavaCommand command, Props props, String httpsProperty, String httpProperty) {
    if (!props.contains(httpsProperty) && props.contains(httpProperty)) {
      command.addJavaOption("-D" + httpsProperty + "=" + props.value(httpProperty));
    }
  }

  static String starPath(File homeDir, String relativePath) {
    File dir = new File(homeDir, relativePath);
    return FilenameUtils.concat(dir.getAbsolutePath(), "*");
  }

  public static void main(String[] args) throws InterruptedException {
    CommandLineParser cli = new CommandLineParser();
    Properties rawProperties = cli.parseArguments(args);
    Props props = new PropsBuilder(rawProperties, new JdbcSettings()).build();
    AppFileSystem appFileSystem = new AppFileSystem(props);
    appFileSystem.verifyProps();
    AppLogging logging = new AppLogging();
    logging.configure(props);

    // used by orchestrator
    boolean watchForHardStop = props.valueAsBoolean(ProcessProperties.ENABLE_STOP_COMMAND, false);
    App app = new App(appFileSystem, watchForHardStop);
    app.start(props);
  }

  @Override
  public void stopAsync() {
    monitor.stop();
  }

  private static class AppLifecycleListener implements Lifecycle.LifecycleListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);

    @Override
    public void successfulTransition(State from, State to) {
      if (to == State.STARTED) {
        LOGGER.info("SonarQube is up");
      }
    }
  }
}
