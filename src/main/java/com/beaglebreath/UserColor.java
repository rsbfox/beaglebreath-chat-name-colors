package com.beaglebreath;

import lombok.EqualsAndHashCode;
import lombok.Value;

import java.awt.*;
import java.util.Date;

@Value
@EqualsAndHashCode
public class UserColor {
    private Color color;
    private String username;
    // This is currently not used, but we can potentially use this to reduce
    // The size of the config when generating colors for random users
    // By introducing a TTL
    private Date lastSeenAt;

    public UserColor touch() {
        return new UserColor(
                color,
                username,
                new Date()
        );
    }
}
