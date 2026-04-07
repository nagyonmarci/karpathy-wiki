package com.danvega.wiki.config;

import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Wires up filesystem, skills, and memory tools from spring-ai-agent-utils 0.7.0.
 * Shared by every feature's agent.
 */
@Configuration
public class ToolsConfig {

    private final WikiProperties props;

    public ToolsConfig(WikiProperties props) {
        this.props = props;
    }

    @Bean
    public FileSystemTools fileSystemTools() {
        return FileSystemTools.builder().build();
    }

    @Bean
    public GrepTool grepTool() {
        return GrepTool.builder().workingDirectory(Path.of(".")).build();
    }

    @Bean
    public GlobTool globTool() {
        return GlobTool.builder().workingDirectory(Path.of(".")).build();
    }

    @Bean
    public ToolCallback skillsTool() {
        return SkillsTool.builder()
                .addSkillsDirectory(props.paths().skills())
                .build();
    }

    @Bean
    public AutoMemoryToolsAdvisor autoMemoryToolsAdvisor() {
        return AutoMemoryToolsAdvisor.builder()
                .memoriesRootDirectory(props.paths().memory())
                .build();
    }
}
