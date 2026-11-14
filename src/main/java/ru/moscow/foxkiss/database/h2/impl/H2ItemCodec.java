package ru.moscow.foxkiss.database.h2.impl;


import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;

import ru.moscow.foxkiss.auction.*;

final class H2ItemCodec {
    static String groupKey(ItemStack item, AuctionLotType type, String inventoryData) {
        ItemStack normalized = item.clone();
        normalized.setAmount(1);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(normalized.serializeAsBytes());
            digest.update((byte) 0);
            digest.update(type.name().getBytes(StandardCharsets.UTF_8));
            if (type == AuctionLotType.INVENTORY) {
                digest.update((byte) 0);
                digest.update(inventoryData.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Недоступен SHA-256 для группировки лотов", e);
        }
    }
    static AuctionItem readItem(ResultSet rs) throws Exception {
        ItemStack item = deserialize(rs.getString("item"));
        int amount = rs.getInt("amount");
        if (amount > 0) {
            item.setAmount(amount);
        }
        AuctionLotType type = AuctionLotType.valueOf(rs.getString("lot_type"));
        List<ItemStack> inventoryContents = deserializeInventory(rs.getString("inventory_data"));
        String opId = null;
        long startedAt = 0L;
        try {
            opId = rs.getString("operation_id");
            startedAt = rs.getLong("processing_started_at");
        } catch (SQLException ignored) {}
        BigDecimal priceBd = rs.getBigDecimal("price");
        double price = priceBd != null ? priceBd.doubleValue() : 0.0;
        return new AuctionItem(
                rs.getLong("id"),
                rs.getString("seller_name"),
                AuctionCurrency.valueOf(rs.getString("currency")),
                item,
                price,
                rs.getLong("created_at"),
                AuctionStatus.fromInt(rs.getInt("status")),
                type,
                inventoryContents,
                opId,
                startedAt
        );
    }

    static String serializeInventory(List<ItemStack> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contents.size(); i++) {
            if (i > 0) {
                sb.append(";");
            }
            ItemStack is = contents.get(i);
            sb.append(is == null || is.getType() == Material.AIR ? "" : serialize(is));
        }
        return sb.toString();
    }

    static List<ItemStack> deserializeInventory(String data) {
        ObjectArrayList<ItemStack> list = new ObjectArrayList<>();
        if (data == null || data.isEmpty()) {
            return list;
        }
        String[] parts = data.split(";", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                list.add(null);
            } else {
                list.add(deserialize(part));
            }
        }
        return list;
    }

    static String serialize(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    static ItemStack deserialize(String data){
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
    }
}
