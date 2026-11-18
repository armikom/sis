/** MCP client configuration snippets for the AI agent page. The server name in every client is "sis". */

export const MCP_SERVER_NAME = 'sis';
export const MCP_TOKEN_ENV = 'SIS_TOKEN';

export const MCP_CLIENTS = [
  { id: 'claude-code', label: 'Claude Code' },
  { id: 'claude-desktop', label: 'Claude Desktop' },
  { id: 'vscode', label: 'VS Code (Copilot)' },
  { id: 'cursor', label: 'Cursor' },
  { id: 'codex', label: 'Codex CLI' },
  { id: 'gemini', label: 'Gemini CLI' },
] as const;

export type McpClientId = (typeof MCP_CLIENTS)[number]['id'];

export function agentSnippet(client: McpClientId, url: string, token: string): { where: string; text: string } {
  const name = MCP_SERVER_NAME;
  const auth = `Bearer ${token}`;
  switch (client) {
    case 'claude-code':
      return {
        where: 'Run in a terminal / Terminalde çalıştır:',
        text: `claude mcp add --transport http --scope user ${name} ${url} \\\n  --header "Authorization: ${auth}"`,
      };
    case 'claude-desktop':
      return {
        where: 'Settings → Developer → Edit Config (claude_desktop_config.json). Needs Node.js. / Node.js gerekir.',
        text: JSON.stringify({ mcpServers: { [name]: { command: 'npx', args: ['-y', 'mcp-remote', url, '--header', 'Authorization:${AUTH_HEADER}'], env: { AUTH_HEADER: auth } } } }, null, 2),
      };
    case 'vscode':
      return {
        where: '.vscode/mcp.json (or "MCP: Open User Configuration"):',
        text: JSON.stringify({ servers: { [name]: { type: 'http', url, headers: { Authorization: auth } } } }, null, 2),
      };
    case 'cursor':
      return {
        where: '~/.cursor/mcp.json:',
        text: JSON.stringify({ mcpServers: { [name]: { url, headers: { Authorization: auth } } } }, null, 2),
      };
    case 'codex':
      return {
        where: `~/.codex/config.toml, then set the variable ${MCP_TOKEN_ENV} / sonra ${MCP_TOKEN_ENV} değişkenini tanımla:`,
        text: `[mcp_servers.${name}]\nurl = "${url}"\nbearer_token_env_var = "${MCP_TOKEN_ENV}"\n\n# shell:\nexport ${MCP_TOKEN_ENV}="${token}"`,
      };
    default:
      return {
        where: '~/.gemini/settings.json:',
        text: JSON.stringify({ mcpServers: { [name]: { httpUrl: url, headers: { Authorization: auth } } } }, null, 2),
      };
  }
}
