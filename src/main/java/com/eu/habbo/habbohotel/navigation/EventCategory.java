package com.eu.habbo.habbohotel.navigation;

import com.eu.habbo.messages.ServerMessage;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class EventCategory {
    private int id;
    private String caption;
    private boolean visible;


    public EventCategory(String serialized) throws Exception {
        String[] parts = serialized.split(",");

        if (parts.length != 3) throw new Exception("A serialized event category should contain 3 fields");

        this.id = Integer.parseInt(parts[0]);
        this.caption = parts[1];
        this.visible = parts[2].equalsIgnoreCase("true");
    }


    public void serialize(ServerMessage message) {
        message.appendInt(this.id);
        message.appendString(this.caption);
        message.appendBoolean(this.visible);
    }
}
