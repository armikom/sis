package com.armikom.sis.agent;

import java.util.List;

/**
 * A bean that contributes tools to the MCP server. {@link McpController} collects every provider, so a new group of
 * tools is one component that implements this interface; the order of the tools in {@code tools/list} follows the
 * bean order ({@code @Order}).
 */
public interface McpToolProvider {

    List<McpTool> tools();
}
