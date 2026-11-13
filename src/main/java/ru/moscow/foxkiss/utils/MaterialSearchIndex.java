package ru.moscow.foxkiss.utils;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.*;

public final class MaterialSearchIndex {
    private final Node root = new Node();

    public MaterialSearchIndex(Map<String, String> translations, Collection<String> materialIds) {
        for (String material : materialIds) {
            char[] alias = material.toCharArray();
            for (int i = 0; i < alias.length; i++) alias[i] = Character.toLowerCase(alias[i]);
            add(material, new String(alias));
        }
        for (Map.Entry<String, String> entry : translations.entrySet()) {
            String name = entry.getValue();
            if (name != null && !name.isBlank()) {
                String plainName = PlainTextComponentSerializer.plainText().serialize(TextUtils.component(name));
                if (!plainName.isBlank()) add(entry.getKey(), plainName);
            }
        }
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            node.edges = new char[node.children.size()];
            int position = 0;
            for (Map.Entry<Character, Node> edge : node.children.entrySet()) {
                node.edges[position++] = edge.getKey();
                pending.push(edge.getValue());
            }
            Arrays.sort(node.edges);
            node.items.sort(Comparator.comparing(Match::material));
        }
    }

    private void add(String material, String name) {
        Node node = root;
        for (int i = 0; i < name.length(); i++) {
            node = node.children.computeIfAbsent(Character.toLowerCase(name.charAt(i)), ignored -> new Node());
        }
        for (Match existing : node.items) {
            if (existing.material().equals(material) && existing.name().equalsIgnoreCase(name)) return;
        }
        node.items.add(new Match(material, name));
    }

    public List<String> findMaterials(String prefix) {
        List<String> ids = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (Match match : find(prefix, Integer.MAX_VALUE)) {
            if (unique.add(match.material())) ids.add(match.material());
        }
        return List.copyOf(ids);
    }

    public List<Match> find(String prefix, int limit) {
        if (limit <= 0 || prefix.isEmpty()) return List.of();
        Node node = root;
        for (int i = 0; i < prefix.length(); i++) {
            node = node.children.get(Character.toLowerCase(prefix.charAt(i)));
            if (node == null) return List.of();
        }
        List<Match> result = new ArrayList<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.push(node);
        while (!pending.isEmpty() && result.size() < limit) {
            Node current = pending.pop();
            for (Match item : current.items) {
                result.add(item);
                if (result.size() == limit) return List.copyOf(result);
            }
            for (int i = current.edges.length - 1; i >= 0; i--) {
                pending.push(current.children.get(current.edges[i]));
            }
        }
        return List.copyOf(result);
    }

    public record Match(String material, String name) {}

    private static final class Node {
        final Map<Character, Node> children = new HashMap<>();
        final List<Match> items = new ArrayList<>();
        char[] edges;
    }
}
