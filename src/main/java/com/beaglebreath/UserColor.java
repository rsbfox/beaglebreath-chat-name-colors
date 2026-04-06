package com.beaglebreath;

import lombok.Value;

import java.awt.*;

@Value
public class UserColor {
    Color color;
    long lastSeenAt;
    transient boolean manuallySet;

    public UserColor touch() {
        return new UserColor(
                color,
                System.currentTimeMillis(),
                manuallySet
        );
    }
}