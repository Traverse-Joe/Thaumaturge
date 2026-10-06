package com.leclowndu93150.thaumaturge.client.model.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import org.joml.Vector3f;

/**
 * Reads a Java block model JSON (elements, faces, single-axis element rotation) into a {@link TCMesh}
 * so block-entity renderers can draw Blockbench exports with their own render types and textures.
 * One part is produced per texture key, named after the key without its '#'.
 */
public final class BlockModelMeshLoader {
    private static final int CORNERS_PER_QUAD = 4;
    private static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

    private BlockModelMeshLoader() {}

    public static TCMesh load(ResourceManager resources, Identifier location) throws IOException {
        JsonObject root;
        try (BufferedReader reader = resources.openAsReader(location)) {
            root = GsonHelper.parse(reader);
        } catch (JsonParseException e) {
            throw new IOException("Malformed block model JSON in " + location, e);
        }
        try {
            return parse(root);
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Invalid block model " + location, e);
        }
    }

    private static TCMesh parse(JsonObject root) {
        Map<String, PartBuilder> parts = new LinkedHashMap<>();
        JsonArray elements = GsonHelper.getAsJsonArray(root, "elements");
        for (int e = 0; e < elements.size(); e++) {
            JsonObject element = GsonHelper.convertToJsonObject(elements.get(e), "element");
            float[] from = vec3(GsonHelper.getAsJsonArray(element, "from"));
            float[] to = vec3(GsonHelper.getAsJsonArray(element, "to"));
            Rotation rotation = element.has("rotation") ? Rotation.parse(GsonHelper.getAsJsonObject(element, "rotation")) : null;
            JsonObject faces = GsonHelper.getAsJsonObject(element, "faces");
            for (String face : FACES) {
                if (!faces.has(face)) {
                    continue;
                }
                JsonObject faceJson = GsonHelper.getAsJsonObject(faces, face);
                String key = GsonHelper.getAsString(faceJson, "texture").replace("#", "");
                float[] uv = faceJson.has("uv") ? vec4(GsonHelper.getAsJsonArray(faceJson, "uv")) : new float[] {0.0F, 0.0F, 16.0F, 16.0F};
                int uvRotation = GsonHelper.getAsInt(faceJson, "rotation", 0);
                parts.computeIfAbsent(key, PartBuilder::new).addFace(face, from, to, uv, uvRotation, rotation);
            }
        }
        List<TCMeshPart> built = new ArrayList<>(parts.size());
        for (PartBuilder part : parts.values()) {
            built.add(part.build());
        }
        return new TCMesh(List.copyOf(built));
    }

    private static float[] vec3(JsonArray array) {
        return new float[] {array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    private static float[] vec4(JsonArray array) {
        return new float[] {array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat(), array.get(3).getAsFloat()};
    }

    private record Rotation(float[] origin, char axis, float radians, boolean rescale) {
        static Rotation parse(JsonObject json) {
            float angle = GsonHelper.getAsFloat(json, "angle");
            String axis = GsonHelper.getAsString(json, "axis");
            return new Rotation(vec3(GsonHelper.getAsJsonArray(json, "origin")), axis.charAt(0), (float) Math.toRadians(angle), GsonHelper.getAsBoolean(json, "rescale", false));
        }

        void apply(Vector3f point) {
            point.sub(origin[0], origin[1], origin[2]);
            switch (axis) {
                case 'x' -> point.rotateX(radians);
                case 'y' -> point.rotateY(radians);
                default -> point.rotateZ(radians);
            }
            if (rescale) {
                float scale = 1.0F / (float) Math.cos(Math.abs(radians));
                switch (axis) {
                    case 'x' -> point.mul(1.0F, scale, scale);
                    case 'y' -> point.mul(scale, 1.0F, scale);
                    default -> point.mul(scale, scale, 1.0F);
                }
            }
            point.add(origin[0], origin[1], origin[2]);
        }

        void applyNormal(Vector3f normal) {
            switch (axis) {
                case 'x' -> normal.rotateX(radians);
                case 'y' -> normal.rotateY(radians);
                default -> normal.rotateZ(radians);
            }
        }
    }

    private static final class PartBuilder {
        private final String name;
        private final List<float[]> quads = new ArrayList<>();

        PartBuilder(String name) {
            this.name = name;
        }

        // Corner order and UV assignment follow vanilla FaceInfo / BlockElementFace so exports render as in Blockbench.
        void addFace(String face, float[] f, float[] t, float[] uv, int uvRotation, Rotation rotation) {
            float x0 = f[0], y0 = f[1], z0 = f[2], x1 = t[0], y1 = t[1], z1 = t[2];
            float[][] corners = switch (face) {
                case "down" -> new float[][] {{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
                case "up" -> new float[][] {{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
                case "north" -> new float[][] {{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
                case "south" -> new float[][] {{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}};
                case "west" -> new float[][] {{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
                default -> new float[][] {{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}};
            };
            Vector3f normal = switch (face) {
                case "down" -> new Vector3f(0.0F, -1.0F, 0.0F);
                case "up" -> new Vector3f(0.0F, 1.0F, 0.0F);
                case "north" -> new Vector3f(0.0F, 0.0F, -1.0F);
                case "south" -> new Vector3f(0.0F, 0.0F, 1.0F);
                case "west" -> new Vector3f(-1.0F, 0.0F, 0.0F);
                default -> new Vector3f(1.0F, 0.0F, 0.0F);
            };
            if (rotation != null) {
                rotation.applyNormal(normal);
            }
            float[][] uvCorners = {{uv[0], uv[1]}, {uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}};
            int shift = Math.floorMod(uvRotation / 90, CORNERS_PER_QUAD);
            float[] quad = new float[CORNERS_PER_QUAD * 8];
            Vector3f point = new Vector3f();
            for (int corner = 0; corner < CORNERS_PER_QUAD; corner++) {
                point.set(corners[corner][0], corners[corner][1], corners[corner][2]);
                if (rotation != null) {
                    rotation.apply(point);
                }
                float[] cornerUv = uvCorners[(corner + shift) % CORNERS_PER_QUAD];
                int o = corner * 8;
                quad[o] = point.x / 16.0F;
                quad[o + 1] = point.y / 16.0F;
                quad[o + 2] = point.z / 16.0F;
                quad[o + 3] = cornerUv[0] / 16.0F;
                // TCMesh stores V bottom-up; renderers flip it back when emitting vertices.
                quad[o + 4] = 1.0F - cornerUv[1] / 16.0F;
                quad[o + 5] = normal.x;
                quad[o + 6] = normal.y;
                quad[o + 7] = normal.z;
            }
            quads.add(quad);
        }

        TCMeshPart build() {
            int vertexCount = quads.size() * CORNERS_PER_QUAD;
            float[] positions = new float[vertexCount * 3];
            float[] uvs = new float[vertexCount * 2];
            float[] normals = new float[vertexCount * 3];
            for (int q = 0; q < quads.size(); q++) {
                float[] quad = quads.get(q);
                for (int corner = 0; corner < CORNERS_PER_QUAD; corner++) {
                    int vertex = q * CORNERS_PER_QUAD + corner;
                    int o = corner * 8;
                    System.arraycopy(quad, o, positions, vertex * 3, 3);
                    System.arraycopy(quad, o + 3, uvs, vertex * 2, 2);
                    System.arraycopy(quad, o + 5, normals, vertex * 3, 3);
                }
            }
            return new TCMeshPart(name, name, quads.size(), positions, uvs, normals);
        }
    }
}
