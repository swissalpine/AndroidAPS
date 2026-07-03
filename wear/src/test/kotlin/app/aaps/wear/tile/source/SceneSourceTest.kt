package app.aaps.wear.tile.source

import android.content.Context
import android.content.res.Resources
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.R
import app.aaps.wear.interaction.actions.BackgroundActionActivity
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [SceneSource] — the Wear tile source that either shows a single "End" button while a
 * scene is active, or maps up to four stored scenes to launch actions.
 *
 * All branches are deterministic (no wall-clock dependency). Fresh mocks are built here (rather than
 * extending WearTestBase) because only [Context], [Resources] and [SP] stubs are required.
 */
class SceneSourceTest {

    private val aapsLogger = AAPSLoggerTest()
    private val context: Context = mock()
    private val resources: Resources = mock()
    private val sp: SP = mock()

    private val endText = "End scene"
    private val confirmationText = "Run this scene?"

    private lateinit var sceneSource: SceneSource

    private fun sceneListRaw(vararg entries: EventData.SceneList.SceneEntry): String =
        EventData.SceneList(ArrayList(entries.toList())).serialize()

    private fun entry(id: String, title: String, ts: Long = 1_000L) =
        EventData.SceneList.SceneEntry(timeStamp = ts, id = id, title = title)

    /** Stubs the raw scene state read by isSceneActive(). Empty string == no active scene. */
    private fun stubActiveSceneState(raw: String) {
        whenever(sp.getString(eq(R.string.key_active_scene_state), eq(""))).thenReturn(raw)
    }

    /** Stubs the raw scene-list data read by getSceneData(). */
    private fun stubSceneData(raw: String) {
        whenever(sp.getString(eq(R.string.key_scene_data), any())).thenReturn(raw)
    }

    @BeforeEach
    fun setup() {
        whenever(context.resources).thenReturn(resources)
        whenever(resources.getString(R.string.scene_end)).thenReturn(endText)
        whenever(resources.getString(R.string.action_scene_confirmation)).thenReturn(confirmationText)
        // Default: no active scene, empty scene list. Individual tests override as needed.
        stubActiveSceneState("")
        stubSceneData(sceneListRaw())
        sceneSource = SceneSource(context, sp, aapsLogger)
    }

    @Test
    fun activeSceneReturnsSingleEndAction() {
        stubActiveSceneState(EventData.ActiveSceneState(active = true).serialize())
        // Even with scenes stored, the active branch wins and returns only the End action.
        stubSceneData(sceneListRaw(entry("a", "Breakfast"), entry("b", "Lunch")))

        val actions = sceneSource.getSelectedActions()

        assertThat(actions).hasSize(1)
        val end = actions.single()
        assertThat(end.buttonText).isEqualTo(endText)
        assertThat(end.iconRes).isEqualTo(R.drawable.ic_cancel_red)
        assertThat(end.activityClass).isEqualTo(BackgroundActionActivity::class.java.name)
        assertThat(end.action).isInstanceOf(EventData.ActionSceneStopPreCheck::class.java)
        assertThat(end.message).isNull()
    }

    @Test
    fun inactiveSceneMapsEntriesToPreCheckActions() {
        stubActiveSceneState(EventData.ActiveSceneState(active = false).serialize())
        stubSceneData(sceneListRaw(entry("id-1", "Breakfast"), entry("id-2", "Lunch")))

        val actions = sceneSource.getSelectedActions()

        assertThat(actions).hasSize(2)
        val first = actions[0]
        assertThat(first.buttonText).isEqualTo("Breakfast")
        assertThat(first.iconRes).isEqualTo(R.drawable.ic_scene_purple)
        assertThat(first.activityClass).isEqualTo(BackgroundActionActivity::class.java.name)
        assertThat(first.message).isEqualTo(confirmationText)
        val action = first.action
        assertThat(action).isInstanceOf(EventData.ActionScenePreCheck::class.java)
        action as EventData.ActionScenePreCheck
        assertThat(action.id).isEqualTo("id-1")
        assertThat(action.title).isEqualTo("Breakfast")

        val second = actions[1].action
        assertThat(second).isInstanceOf(EventData.ActionScenePreCheck::class.java)
        second as EventData.ActionScenePreCheck
        assertThat(second.id).isEqualTo("id-2")
        assertThat(second.title).isEqualTo("Lunch")
    }

    @Test
    fun emptyActiveStateTreatedAsInactive() {
        // Empty raw -> isSceneActive() returns false directly, so scenes are mapped.
        stubActiveSceneState("")
        stubSceneData(sceneListRaw(entry("only", "Snack")))

        val actions = sceneSource.getSelectedActions()

        assertThat(actions).hasSize(1)
        assertThat(actions.single().buttonText).isEqualTo("Snack")
        assertThat(actions.single().iconRes).isEqualTo(R.drawable.ic_scene_purple)
    }

    @Test
    fun malformedActiveStateTreatedAsInactive() {
        // Garbage raw -> deserialize yields Error, runCatching guards -> false -> scenes mapped.
        stubActiveSceneState("this is not valid json {")
        stubSceneData(sceneListRaw(entry("x", "Dinner")))

        val actions = sceneSource.getSelectedActions()

        assertThat(actions).hasSize(1)
        assertThat(actions.single().buttonText).isEqualTo("Dinner")
    }

    @Test
    fun mapsAtMostFourScenes() {
        stubActiveSceneState("")
        stubSceneData(
            sceneListRaw(
                entry("1", "One"),
                entry("2", "Two"),
                entry("3", "Three"),
                entry("4", "Four"),
                entry("5", "Five"),
                entry("6", "Six"),
            )
        )

        val actions = sceneSource.getSelectedActions()

        assertThat(actions).hasSize(4)
        assertThat(actions.map { it.buttonText }).containsExactly("One", "Two", "Three", "Four").inOrder()
    }

    @Test
    fun noScenesReturnsEmptyList() {
        stubActiveSceneState("")
        stubSceneData(sceneListRaw())

        assertThat(sceneSource.getSelectedActions()).isEmpty()
    }

    @Test
    fun getValidForIsNull() {
        assertThat(sceneSource.getValidFor()).isNull()
    }

    @Test
    fun getResourceReferencesContainsBothDrawables() {
        val refs = sceneSource.getResourceReferences(resources)

        assertThat(refs).containsExactly(R.drawable.ic_scene_purple, R.drawable.ic_cancel_red)
    }
}
