package com.example

import com.example.data.BuiltInCatalog
import com.example.engine.KeyframeEvaluator
import com.example.model.*
import com.example.viewmodel.ProjectMutator
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class AnimationEngineTest {

    @Test
    fun `linear and hold keyframe interpolation evaluates deterministically`() {
        val clip = TimelineClip(
            layerId = "l1",
            name = "Test",
            startFrame = 10,
            durationFrames = 60,
            keyframes = listOf(
                TransformKeyframe(frame = 0, x = 0.2f, y = 0.5f, rotation = 0f, scaleX = 1f, scaleY = 1f, opacity = 1f, easing = EasingType.LINEAR),
                TransformKeyframe(frame = 20, x = 0.6f, y = 0.5f, rotation = 90f, scaleX = 2f, scaleY = 2f, opacity = 0.5f, easing = EasingType.HOLD),
                TransformKeyframe(frame = 40, x = 0.9f, y = 0.8f, rotation = 180f, scaleX = 1f, scaleY = 1f, opacity = 1f, easing = EasingType.LINEAR)
            )
        )

        // Midpoint of linear segment (global frame 20 -> local frame 10)
        val midLinear = KeyframeEvaluator.evaluate(clip, globalFrame = 20)
        assertEquals(0.4f, midLinear.x, 0.001f)
        assertEquals(45f, midLinear.rotation, 0.01f)
        assertEquals(1.5f, midLinear.scaleX, 0.001f)
        assertEquals(0.75f, midLinear.opacity, 0.001f)

        // Inside HOLD segment (global frame 40 -> local frame 30) should hold keyframe at local frame 20
        val midHold = KeyframeEvaluator.evaluate(clip, globalFrame = 40)
        assertEquals(0.6f, midHold.x, 0.001f)
        assertEquals(90f, midHold.rotation, 0.01f)

        // End keyframe (global frame 50 -> local frame 40) jumps to target
        val endKf = KeyframeEvaluator.evaluate(clip, globalFrame = 50)
        assertEquals(0.9f, endKf.x, 0.001f)
        assertEquals(180f, endKf.rotation, 0.01f)
    }

    @Test
    fun `project mutator adds character, splits clip, duplicates clip, and round-trips JSON`() {
        val blank = BuiltInCatalog.sceneTemplates.last().buildProject("Studio Test")
        val aarav = BuiltInCatalog.builtInCharacters.first()

        val (withChar, clipId) = ProjectMutator.addCharacterClip(
            project = blank,
            character = aarav,
            orientation = CharacterOrientation.THREE_QUARTER_FRONT,
            preset = AnimationPresetType.WALK,
            startFrame = 0,
            durationFrames = 60,
            speedMultiplier = 1.2f,
            loopAnimation = true
        )
        assertEquals(1, withChar.activeScene.clips.size)

        val split = ProjectMutator.splitClipAtFrame(withChar, clipId, globalFrame = 30)
        assertEquals(2, split.activeScene.clips.size)
        assertEquals(30, split.activeScene.clips[0].durationFrames)
        assertEquals(30, split.activeScene.clips[1].startFrame)

        val (duplicated, dupId) = ProjectMutator.duplicateClip(split, clipId)
        assertNotNull(dupId)
        assertEquals(3, duplicated.activeScene.clips.size)

        // Verify full Moshi JSON round-trip
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ProjectDocument::class.java)
        val json = adapter.toJson(duplicated)
        val restored = adapter.fromJson(json)
        assertNotNull(restored)
        assertEquals("Studio Test", restored?.name)
        assertEquals(3, restored?.activeScene?.clips?.size)
        assertEquals(AnimationPresetType.WALK, restored?.activeScene?.clips?.first()?.animationPresetType)
    }

    @Test
    fun `gif encoder produces valid animated GIF89a binary stream`() {
        val encoder = com.example.engine.GifEncoder(width = 32, height = 24, delayCentiseconds = 4, loopCount = 0)
        encoder.start()
        val frame1 = IntArray(32 * 24) { idx -> if (idx % 2 == 0) 0xFF38BDF8.toInt() else 0xFF141822.toInt() }
        val frame2 = IntArray(32 * 24) { idx -> if (idx % 3 == 0) 0xFFF59E0B.toInt() else 0xFF0A0D12.toInt() }
        encoder.addPixels(frame1)
        encoder.addPixels(frame2)

        val baos = java.io.ByteArrayOutputStream()
        encoder.finish(baos)
        val bytes = baos.toByteArray()
        assertTrue(bytes.size > 800)
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B.toByte(), bytes.last())
    }

    @Test
    fun `export dimensions preserve aspect ratio and even alignment`() {
        val (w16, h16) = com.example.engine.AnimationExporter.computeExportDimensions(1280, 720, 1280)
        assertEquals(1280, w16)
        assertEquals(720, h16)
        val (w9, h9) = com.example.engine.AnimationExporter.computeExportDimensions(720, 1280, 1280)
        assertEquals(720, w9)
        assertEquals(1280, h9)
    }

    @Test
    fun `hash router navigates across studio views and pops back stack`() {
        val router = com.example.navigation.HashRouter()
        assertEquals(com.example.navigation.HashRoute.ProjectDashboard, router.currentRoute)

        router.navigate(com.example.navigation.HashRoute.AnimationEditor("proj_42"))
        assertEquals("#/editor/proj_42", router.currentHash)
        assertEquals(com.example.navigation.HashRoute.AnimationEditor("proj_42"), router.currentRoute)

        router.navigate(com.example.navigation.HashRoute.CharacterLibrary)
        assertEquals(com.example.navigation.HashRoute.CharacterLibrary, router.currentRoute)

        router.navigate(com.example.navigation.HashRoute.AnimationLibrary("char_aarav_desi", swapClipId = "clip_7"))
        val parsedAnim = router.currentRoute as com.example.navigation.HashRoute.AnimationLibrary
        assertEquals("char_aarav_desi", parsedAnim.characterId)
        assertEquals("clip_7", parsedAnim.swapClipId)

        assertTrue(router.popBackStack())
        assertEquals(com.example.navigation.HashRoute.CharacterLibrary, router.currentRoute)
        router.popUpTo(com.example.navigation.HashRoute.HASH_EDITOR)
        assertEquals(com.example.navigation.HashRoute.AnimationEditor("proj_42"), router.currentRoute)
    }

    @Test
    fun `editor canvas hit testing selects topmost unlocked visible clip`() {
        val project = BuiltInCatalog.sceneTemplates.first().buildProject("Hit Test")
        val scene = project.activeScene
        val hit = com.example.ui.screens.editor.hitTestClipAtPoint(scene, frame = 0, nx = 0.22f, ny = 0.68f)
        assertNotNull(hit)
        assertEquals("char_aarav_desi", hit?.characterId)

        val miss = com.example.ui.screens.editor.hitTestClipAtPoint(scene, frame = 0, nx = 0.95f, ny = 0.05f)
        assertNull(miss)
    }

    @Test
    fun `skeleton rig config round-trips JSON and human rig defaults are populated`() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(SkeletonRigConfig::class.java)
        val rig = SkeletonRigConfig(
            enabled = true,
            removeBackground = true,
            bgTolerance = 48,
            showBoneOverlay = true,
            headX = 0.52f,
            headY = 0.16f,
            leftHandX = 0.18f,
            rightFootY = 0.94f
        )
        val json = adapter.toJson(rig)
        val decoded = adapter.fromJson(json)
        assertNotNull(decoded)
        assertTrue(decoded!!.enabled)
        assertTrue(decoded.removeBackground)
        assertEquals(48, decoded.bgTolerance)
        assertEquals(0.52f, decoded.headX, 0.0001f)
        assertEquals(0.94f, decoded.rightFootY, 0.0001f)

        val aarav = BuiltInCatalog.builtInCharacters.first { it.id == "char_aarav_desi" }
        assertEquals(HumanCostumeStyle.KURTA_TRADITIONAL, aarav.costumeStyle)
        assertEquals(HumanHairStyle.SIDE_PART, aarav.hairStyle)
        assertEquals(HumanBuildType.ATHLETIC, aarav.buildType)
    }

    @Test
    fun `timeline layer visibility toggle updates layer state and blocks hit testing when hidden`() {
        val project = BuiltInCatalog.sceneTemplates.first().buildProject("Layer Visibility Test")
        val charLayer = project.activeScene.layers.first { it.type == LayerType.CHARACTER }
        assertTrue(charLayer.isVisible)

        val hiddenProject = ProjectMutator.updateActiveScene(project) { scene ->
            scene.copy(layers = scene.layers.map { if (it.id == charLayer.id) it.copy(isVisible = !it.isVisible) else it })
        }
        val updatedLayer = hiddenProject.activeScene.layers.first { it.id == charLayer.id }
        assertFalse(updatedLayer.isVisible)

        // Hidden character layer should not be hit-testable on the canvas
        val hitWhileHidden = com.example.ui.screens.editor.hitTestClipAtPoint(
            hiddenProject.activeScene, frame = 0, nx = 0.22f, ny = 0.68f
        )
        assertNull(hitWhileHidden)
    }

    @Test
    fun `skeleton mesh deformer computes normalized LBS weights and continuous FK joints`() {
        val skel = SkeletonRigConfig(enabled = true)
        val skin = com.example.engine.SkeletonMeshDeformer.buildSkinDataFromMask(
            skeleton = skel,
            alphaMask = null,
            aspect = 0.65f
        )

        // Every vertex's top-3 skinning weights must be non-negative and sum strictly to 1.0
        val vertCount = skin.restU.size
        assertTrue(vertCount > 500)
        for (i in 0 until vertCount) {
            val o = i * 3
            val w0 = skin.boneWeight[o]
            val w1 = skin.boneWeight[o + 1]
            val w2 = skin.boneWeight[o + 2]
            assertTrue(w0 >= 0f && w1 >= 0f && w2 >= 0f)
            assertEquals(1f, w0 + w1 + w2, 0.001f)
        }

        // Hierarchical FK: shoulder -> elbow -> hand must stay connected (forearm start == upper arm end)
        val mat = FloatArray(11 * 6)
        val posed = FloatArray(11 * 4)
        val wavePose = com.example.engine.ProceduralRigRenderer.PoseAngles(
            bodyTiltDeg = 6f,
            headNodDeg = -8f,
            rightArmDeg = -85f,
            rightForearmDeg = -45f,
            leftLegDeg = 18f,
            leftKneeDeg = 22f
        )
        com.example.engine.SkeletonMeshDeformer.solveForwardKinematics(
            boneRest = skin.boneRest,
            aspect = 0.65f,
            pose = wavePose,
            outMat = mat,
            outPosedJoints = posed
        )
        // Right upper arm (bone 5) distal joint == Right forearm (bone 6) proximal joint
        assertEquals(posed[5 * 4 + 2], posed[6 * 4], 0.0005f)
        assertEquals(posed[5 * 4 + 3], posed[6 * 4 + 1], 0.0005f)
        // Left thigh (bone 7) distal joint == Left shin (bone 8) proximal joint
        assertEquals(posed[7 * 4 + 2], posed[8 * 4], 0.0005f)
        assertEquals(posed[7 * 4 + 3], posed[8 * 4 + 1], 0.0005f)
    }

    @Test
    fun `ik pose keyframe offsets and facial expression overrides interpolate accurately`() {
        val clip = TimelineClip(
            layerId = "l1",
            name = "Hero Acting",
            startFrame = 0,
            durationFrames = 40,
            facialExpression = FacialExpression.HAPPY,
            propType = CharacterPropType.MAGIC_WAND,
            propAttachment = PropAttachmentPoint.RIGHT_HAND,
            keyframes = listOf(
                TransformKeyframe(
                    frame = 0,
                    x = 0.5f,
                    y = 0.7f,
                    poseOffsets = PoseKeyframeOffsets(headNodOffset = -10f, rightArmOffset = -40f, rightForearmOffset = -20f),
                    expressionOverride = FacialExpression.ANGRY
                ),
                TransformKeyframe(
                    frame = 20,
                    x = 0.5f,
                    y = 0.7f,
                    poseOffsets = PoseKeyframeOffsets(headNodOffset = 20f, rightArmOffset = 40f, rightForearmOffset = 10f),
                    expressionOverride = FacialExpression.SHOCKED
                )
            )
        )

        val mid = KeyframeEvaluator.evaluate(clip, globalFrame = 10)
        assertEquals(5f, mid.poseOffsets.headNodOffset, 0.01f)
        assertEquals(0f, mid.poseOffsets.rightArmOffset, 0.01f)
        assertEquals(-5f, mid.poseOffsets.rightForearmOffset, 0.01f)
        assertEquals(FacialExpression.ANGRY, mid.expressionOverride)

        val end = KeyframeEvaluator.evaluate(clip, globalFrame = 20)
        assertEquals(20f, end.poseOffsets.headNodOffset, 0.01f)
        assertEquals(40f, end.poseOffsets.rightArmOffset, 0.01f)
        assertEquals(FacialExpression.SHOCKED, end.expressionOverride)
    }

    @Test
    fun `camera keyframe evaluation and project mutator camera and ik pose operations work`() {
        val baseProject = BuiltInCatalog.sceneTemplates.first().buildProject("Camera & IK Test")
        val p1 = ProjectMutator.upsertCameraKeyframeAtFrame(baseProject, globalFrame = 0) {
            it.copy(x = 0.5f, y = 0.5f, zoom = 1.0f, rotation = 0f, easing = EasingType.LINEAR)
        }
        val p2 = ProjectMutator.upsertCameraKeyframeAtFrame(p1, globalFrame = 20) {
            it.copy(x = 0.3f, y = 0.4f, zoom = 2.0f, rotation = 10f, easing = EasingType.LINEAR)
        }

        val midCam = KeyframeEvaluator.evaluateCamera(p2.activeScene.cameraKeyframes, globalFrame = 10)
        assertEquals(0.4f, midCam.x, 0.001f)
        assertEquals(0.45f, midCam.y, 0.001f)
        assertEquals(1.5f, midCam.zoom, 0.001f)
        assertEquals(5f, midCam.rotation, 0.01f)
        assertFalse(midCam.isIdentity)

        // Verify IK drag solver produces modified pose angle offsets
        val testClip = p2.activeScene.clips.first { it.characterId != null }
        val testRig = BuiltInCatalog.builtInCharacters.first()
        val evalTransform = KeyframeEvaluator.evaluate(testClip, globalFrame = 0)
        val basePose = com.example.engine.ProceduralRigRenderer.PoseAngles()
        val solved = com.example.engine.PropAndModularChartRenderer.solveIkDragToOffsets(
            handleId = IkHandleId.RIGHT_HAND,
            targetNx = 0.72f,
            targetNy = 0.25f,
            clip = testClip,
            rig = testRig,
            transform = evalTransform,
            basePose = basePose,
            currentOffsets = PoseKeyframeOffsets(),
            stageWidth = 1280f,
            stageHeight = 720f
        )
        assertNotEquals(0f, solved.rightArmOffset)
        assertEquals(10, com.example.engine.PropAndModularChartRenderer.MODULAR_CHART_ZONES.size)
    }
}
