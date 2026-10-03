package com.goose.pinshift

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class AppUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun launchesWithoutCrashingAndShowsMainActions() {
        composeRule.onNodeWithText("GOOSEROUTE").assertExists()
        composeRule.onNodeWithText("FIND YOUR LOCATION").assertExists()
        composeRule.onNodeWithText("MOVE HERE").assertExists()
        composeRule.onNodeWithText("STOP").assertExists()
        composeRule.onNodeWithText("APPLY COORDINATES").assertExists()
    }

    @Test fun enteringRouteTabShowsRealRoadControls() {
        composeRule.onNodeWithText("ROUTE SIMULATOR").performClick()
        composeRule.onNodeWithText("REAL ROAD & PATH ROUTING").assertExists()
        composeRule.onNodeWithText("GENERATE FITTED ROAD ROUTE").assertExists()
        composeRule.onNodeWithText("START ROUTE").assertExists()
        composeRule.onNodeWithText("+ Waypoint").assertExists()
        composeRule.onNodeWithText("Fit route to required distance").assertExists()
        composeRule.onNodeWithText("Walk").assertExists()
        composeRule.onNodeWithText("Cycle").assertExists()
        composeRule.onNodeWithText("Drive").assertExists()
    }

    @Test fun switchingRouteBackToStaticRestoresMoveButton() {
        composeRule.onNodeWithText("ROUTE SIMULATOR").performClick()
        composeRule.onNodeWithText("STATIC LOCATION").performClick()
        composeRule.onNodeWithText("MOVE HERE").assertExists()
    }
}
