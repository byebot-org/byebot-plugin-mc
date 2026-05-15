package dev.ronaldzav.byebot.room;

import java.util.List;

public record RoomRegion(String id, String name, String code, boolean preferred, List<RoomServer> servers) {}
