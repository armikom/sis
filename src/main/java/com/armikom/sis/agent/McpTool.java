package com.armikom.sis.agent;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One MCP tool: its name, the JSON schema of its arguments, who may use it, and the code that runs it. A tool returns
 * any value that Jackson can write; the MCP server sends it to the agent as JSON text. Every schema made with
 * {@link #schema} also has the optional {@code course} argument (course code or id).
 */
public record McpTool(String name, String title, String description, Map<String, Object> inputSchema,
                      Audience audience, boolean readOnly, boolean destructive, Handler handler) {

    /** STUDENT tools need a student enrollment in the course, INSTRUCTOR tools a staff role; ALL tools any role. */
    public enum Audience { STUDENT, INSTRUCTOR, ALL }

    @FunctionalInterface
    public interface Handler {
        Object call(McpCall call) throws Exception;
    }

    static McpTool read(Audience audience, String name, String title, String description, Map<String, Object> schema,
                        Handler handler) {
        return new McpTool(name, title, description, schema, audience, true, false, handler);
    }

    static McpTool write(Audience audience, String name, String title, String description,
                         Map<String, Object> schema, Handler handler) {
        return new McpTool(name, title, description, schema, audience, false, false, handler);
    }

    Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("title", title);
        out.put("description", description);
        out.put("inputSchema", inputSchema);
        out.put("annotations", Map.of("title", title, "readOnlyHint", readOnly, "destructiveHint", destructive,
                "idempotentHint", readOnly, "openWorldHint", false));
        return out;
    }

    // ---------- schema helpers ----------

    static final Map<String, Object> COURSE = Map.of("type", "string", "description",
            "Course code (for example SENG403) or course id. Needed only when you have more than one course with "
                    + "the question pool; see list_courses.");

    /**
     * An object schema. Pass pairs of (name, property schema); names that end with "!" are required. The optional
     * {@code course} property is always added.
     */
    static Map<String, Object> schema(Object... pairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        properties.put("course", COURSE);
        for (int i = 0; i < pairs.length; i += 2) {
            String name = (String) pairs[i];
            if (name.endsWith("!")) {
                name = name.substring(0, name.length() - 1);
                required.add(name);
            }
            properties.put(name, pairs[i + 1]);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", properties);
        if (!required.isEmpty()) {
            out.put("required", required);
        }
        out.put("additionalProperties", false);
        return out;
    }

    static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    static Map<String, Object> oneOf(String description, String... values) {
        return Map.of("type", "string", "enum", List.of(values), "description", description);
    }

    static Map<String, Object> intList(String description) {
        return Map.of("type", "array", "items", Map.of("type", "integer"), "description", description);
    }

    static Map<String, Object> chapter() {
        return integer("Chapter (week) number in the course, see list_chapters.");
    }

    // ---------- arguments ----------

    /** The "arguments" object of a tools/call request, with readers that give clear errors to the agent. */
    public record Args(JsonNode node) {

        public boolean has(String name) {
            JsonNode v = node.path(name);
            return !v.isMissingNode() && !v.isNull();
        }

        public String str(String name) {
            String v = optStr(name);
            if (v == null || v.isBlank()) {
                throw new IllegalArgumentException("The argument '" + name + "' is required.");
            }
            return v;
        }

        public String optStr(String name) {
            JsonNode v = node.path(name);
            if (v.isMissingNode() || v.isNull()) {
                return null;
            }
            if (!v.isValueNode()) {
                throw new IllegalArgumentException("The argument '" + name + "' must be text.");
            }
            return v.asString();
        }

        public long num(String name) {
            Long v = optNum(name);
            if (v == null) {
                throw new IllegalArgumentException("The argument '" + name + "' is required.");
            }
            return v;
        }

        public Long optNum(String name) {
            JsonNode v = node.path(name);
            if (v.isMissingNode() || v.isNull()) {
                return null;
            }
            if (v.isIntegralNumber()) {
                return v.asLong();
            }
            if (v.isString() && v.asString().trim().matches("-?\\d+")) {
                return Long.parseLong(v.asString().trim());
            }
            throw new IllegalArgumentException("The argument '" + name + "' must be a whole number.");
        }

        public Integer optInt(String name) {
            Long v = optNum(name);
            return v == null ? null : Math.toIntExact(v);
        }

        public Boolean optBool(String name) {
            JsonNode v = node.path(name);
            if (v.isMissingNode() || v.isNull()) {
                return null;
            }
            if (v.isBoolean()) {
                return v.asBoolean();
            }
            if (v.isString() && List.of("true", "false").contains(v.asString().trim().toLowerCase(Locale.ROOT))) {
                return Boolean.parseBoolean(v.asString().trim());
            }
            throw new IllegalArgumentException("The argument '" + name + "' must be true or false.");
        }

        /** A list of whole numbers, or null when the argument is missing. */
        public List<Long> optLongList(String name) {
            JsonNode v = node.path(name);
            if (v.isMissingNode() || v.isNull()) {
                return null;
            }
            if (!v.isArray()) {
                throw new IllegalArgumentException("The argument '" + name + "' must be a list of whole numbers.");
            }
            List<Long> out = new ArrayList<>();
            for (JsonNode e : v) {
                if (e.isIntegralNumber()) {
                    out.add(e.asLong());
                } else if (e.isString() && e.asString().trim().matches("\\d+")) {
                    out.add(Long.parseLong(e.asString().trim()));
                } else {
                    throw new IllegalArgumentException("The argument '" + name + "' must be a list of whole numbers.");
                }
            }
            return out;
        }

        public JsonNode raw(String name) {
            return node.path(name);
        }
    }
}
