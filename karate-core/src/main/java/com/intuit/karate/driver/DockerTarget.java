/*
 * The MIT License
 *
 * Copyright 2022 Karate Labs Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate.driver;

import com.intuit.karate.FileUtils;
import com.intuit.karate.KarateException;
import com.intuit.karate.StringUtils;
import com.intuit.karate.core.ScenarioRuntime;
import com.intuit.karate.shell.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 *
 * @author pthomas3
 */
public class DockerTarget implements Target {

    private final String imageId;
    private String containerId;
    private Function<Integer, String> command;
    private final Map<String, Object> options;
    private boolean pull = false;
    private boolean karateChrome = false;

    static final Logger logger = LoggerFactory.getLogger(DockerTarget.class);


    public DockerTarget(String dockerImage) {
        this(Collections.singletonMap("docker", dockerImage));
    }

    public DockerTarget(Map<String, Object> options) {
        this.options = options;
        if (options != null) {
            imageId = (String) options.get("docker");
            Integer vncPort = (Integer) options.get("vncPort");
            String secComp = (String) options.get("secComp");
            Boolean temp = (Boolean) options.get("pull");
            pull = temp == null ? false : temp;
            StringBuilder sb = new StringBuilder();
            sb.append("docker run -d -e KARATE_SOCAT_START=true");
            if (secComp == null) {
                sb.append(" --cap-add=SYS_ADMIN");
            } else {
                sb.append(" --security-opt seccomp=").append(secComp);
            }
            if (vncPort != null) {
                sb.append(" -p ").append(vncPort).append(":5900");
            }
            if (imageId != null) {
                if (imageId.contains("/chrome-headless")) {
                    command = p -> sb.toString() + " -p 9222 " + imageId;
                } else if (imageId.contains("/karate-chrome")) {
                    karateChrome = true;
                    command = p -> sb.toString() + " -p 9222 " + imageId;
                }
            }
        } else {
            imageId = null;
        }
    }

    public void setCommand(Function<Integer, String> command) {
        this.command = command;
    }

    public Function<Integer, String> getCommand() {
        return command;
    }

    @Override
    public Map<String, Object> start(ScenarioRuntime sr) {
        if (command == null) {
            throw new RuntimeException("docker target command (function) not set");
        }
        if (imageId != null && pull) {
            sr.logger.debug("attempting to pull docker image: {}", imageId);
            Command.execLine(null, "docker pull " + imageId);
        }
        containerId = Command.execLine(null, command.apply(null));
        int port = this.getContainerPort(containerId);
        Map<String, Object> map = new HashMap();
        if (options != null) {
            map.putAll(options);
        }

        boolean remoteHost = options != null && options.get("remoteHost") != null && (Boolean) options.get("remoteHost");
        boolean useDockerHost = options != null && options.get("useDockerHost") != null && (Boolean) options.get("useDockerHost");
        String host = "127.0.0.1";

        if (remoteHost) {
            String gateway = Command.execLine(null, "docker inspect -f '{{.NetworkSettings.Gateway}}' " + containerId);
            host = gateway.replaceAll("'", ""); // Some responses are wrapped in single quotes.
        } else if (useDockerHost) {
            host = "host.docker.internal";
        }

        map.put("start", false);
        map.put("host", host);
        map.put("port", port);
        map.put("type", "chrome");
        Command.waitForHttp("http://" + host + ":" + port + "/json");
        return map;
    }


    @Override
    public Map<String, Object> stop(ScenarioRuntime sr) {
        Command.execLine(null, "docker stop " + containerId);
        if (!karateChrome) { // no video
            Command.execLine(null, "docker rm " + containerId);
            return Collections.EMPTY_MAP;
        }
        String shortName = containerId.contains("_") ? containerId : StringUtils.truncate(containerId, 12, false);
        String dirName = "karate-chrome_" + shortName;
        String buildDir = sr.featureRuntime.suite.buildDir;
        String resultsDir = buildDir + File.separator + dirName;
        Command.execLine(null, "docker cp " + containerId + ":/tmp " + resultsDir);
        Command.execLine(null, "docker rm " + containerId);
        String video = resultsDir + File.separator + "karate.mp4";
        File file = new File(video);
        if (!file.exists()) {
            sr.logger.warn("video file missing: {}", file);
            return Collections.EMPTY_MAP;
        }
        File copy = new File(buildDir + File.separator + dirName + ".mp4");
        FileUtils.copy(file, copy);
        return Collections.singletonMap("video", copy.getPath());
    }

    private int getContainerPort(String containerId) {
        // 9222/tcp is the default port for chrome headless
        String format = "--format='{{(index (index .NetworkSettings.Ports \"9222/tcp\") 0).HostPort}}'";

        if (FileUtils.isOsWindows()) {
            format = format.replace("\"", "\\\"");
        }

        logger.debug("cmd: docker format {} {}", format, containerId);
        String dockerInspect = Command.exec(false, (File) null,
                "docker", "inspect", format, containerId
        );

        // Certain OS responses come back with single quotes with Command.exec
        dockerInspect = dockerInspect.replaceAll("[^\\d]", "");
        logger.debug("docker inspect command output: {}", dockerInspect);
        try {
            return Integer.parseInt(dockerInspect);
        } catch (NumberFormatException e) {
            throw new KarateException("Error fetching port from started docker container", e);
        }
    }

    public String getContainerId() {
        return this.containerId;
    }
}
