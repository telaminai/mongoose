/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Data
public class GetDefaultHandlerGroupConfigTest {

    private MongooseServerConfig mongooseServerConfig;

    @BeforeEach
    public void setup() {
        mongooseServerConfig = new MongooseServerConfig();
    }

    @Test
    public void testRetrieveExistingGroup() {
        //Given
        String groupName = "testGroup";
        EventProcessorGroupConfig existingGroup = EventProcessorGroupConfig.builder()
                .agentName(groupName)
                .build();
        mongooseServerConfig.getEventHandlers().add(existingGroup);

        //When
        EventProcessorGroupConfig retrievedGroup = mongooseServerConfig.getGroupConfig(groupName);

        //Then
        assertEquals(existingGroup, retrievedGroup);
    }

    @Test
    public void testCreateNewGroup() {
        //Given
        String groupName = "newGroup";

        //When
        EventProcessorGroupConfig newGroup = mongooseServerConfig.getGroupConfig(groupName);

        //Then
        assertNotNull(newGroup);
        assertEquals(groupName, newGroup.getAgentName());
        assertTrue(mongooseServerConfig.getEventHandlers().contains(newGroup));
    }
}
