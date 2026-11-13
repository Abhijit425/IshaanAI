package com.ishaan.ai.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ishaan.ai.data.db.ExtractedLabResult
import com.ishaan.ai.data.db.Verification
import com.ishaan.ai.ui.components.ChartPoint
import com.ishaan.ai.ui.components.ConfirmDialog
import com.ishaan.ai.ui.components.TrendChart
import com.ishaan.ai.ui.reports.LabRow
import com.ishaan.ai.ui.theme.IshaanTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class UiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun unverifiedOcrValueIsClearlyLabelled() {
        val l = ExtractedLabResult(childId = 1, testCode = "CREATININE", displayName = "", valueText = "0.42", unit = "mg/dL", refText = "0.2-0.43", sampleDate = 0, verification = Verification.UNVERIFIED)
        rule.setContent { IshaanTheme { LabRow(l, onClick = {}, onVerify = {}) } }
        rule.onNodeWithText("OCR · unverified").assertIsDisplayed()
        rule.onNodeWithText("0.42 mg/dL").assertIsDisplayed()
    }

    @Test fun deleteNeedsConfirmation() {
        var deleted = false
        rule.setContent { IshaanTheme { ConfirmDialog("Delete this record?", "Permanent.", onConfirm = { deleted = true }, onDismiss = {}) } }
        rule.onNodeWithText("Delete this record?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        assertTrue(deleted)
    }

    @Test fun chartShowsEmptyStateWithoutData() {
        rule.setContent { IshaanTheme { TrendChart(emptyList<ChartPoint>()) } }
        rule.onNodeWithText("No values to chart yet.").assertIsDisplayed()
    }
}
