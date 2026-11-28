package com.example

import com.example.data.BuiltInCatalog
import com.example.engine.KeyframeEvaluator
import com.example.engine.ProceduralRigRenderer
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

    @Test
    fun `camera focus tracking follows moving character clip and preserves zoom and framing offset`() {
        val movingClip = TimelineClip(
            id = "clip_runner",
            layerId = "layer_char",
            name = "Aarav Runner",
            characterId = "char_aarav_desi",
            startFrame = 0,
            durationFrames = 60,
            keyframes = listOf(
                TransformKeyframe(frame = 0, x = 0.2f, y = 0.6f, easing = EasingType.LINEAR),
                TransformKeyframe(frame = 20, x = 0.8f, y = 0.4f, easing = EasingType.LINEAR)
            )
        )
        val scene = AnimationScene(
            id = "scene_track",
            name = "Track Scene",
            cameraFocusClipId = "clip_runner",
            cameraKeyframes = listOf(
                CameraKeyframe(frame = 0, x = 0.55f, y = 0.45f, zoom = 1.6f, rotation = 4f, easing = EasingType.LINEAR)
            ),
            layers = listOf(AnimationLayer(id = "layer_char", name = "Char", type = LayerType.CHARACTER)),
            clips = listOf(movingClip)
        )

        // At frame 0: clip is at (0.2, 0.6), camera offset is (+0.05, -0.05) -> tracked focus (0.25, 0.55)
        val camStart = KeyframeEvaluator.evaluateCamera(scene, globalFrame = 0)
        assertEquals(0.25f, camStart.x, 0.001f)
        assertEquals(0.55f, camStart.y, 0.001f)
        assertEquals(1.6f, camStart.zoom, 0.001f)
        assertEquals("Aarav Runner", camStart.trackedClipName)

        // At frame 10: clip is at midpoint (0.5, 0.5), offset (+0.05, -0.05) -> tracked focus (0.55, 0.45)
        val camMid = KeyframeEvaluator.evaluateCamera(scene, globalFrame = 10)
        assertEquals(0.55f, camMid.x, 0.001f)
        assertEquals(0.45f, camMid.y, 0.001f)

        // Live drag delta preview shifts tracked focus immediately without mutating scene
        val camDragging = KeyframeEvaluator.evaluateCamera(
            scene = scene,
            globalFrame = 10,
            clipDeltaOverrideId = "clip_runner",
            clipDeltaX = 0.1f,
            clipDeltaY = -0.1f
        )
        assertEquals(0.65f, camDragging.x, 0.001f)
        assertEquals(0.35f, camDragging.y, 0.001f)

        // Verify cameraFocusClipId round-trips through Moshi JSON
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(AnimationScene::class.java)
        val restored = adapter.fromJson(adapter.toJson(scene))
        assertEquals("clip_runner", restored?.cameraFocusClipId)
    }

    @Test
    fun `sprite atlas slots are strictly within 0 to 1 and have zero overlapping regions`() {
        val slots = AtlasPartSlot.entries
        assertEquals(30, slots.size)

        for (slot in slots) {
            assertTrue("${slot.id} u0 out of range", slot.u0 in 0f..1f)
            assertTrue("${slot.id} v0 out of range", slot.v0 in 0f..1f)
            assertTrue("${slot.id} u1 out of range", slot.u1 in 0f..1f)
            assertTrue("${slot.id} v1 out of range", slot.v1 in 0f..1f)
            assertTrue("${slot.id} width must be positive", slot.u1 > slot.u0)
            assertTrue("${slot.id} height must be positive", slot.v1 > slot.v0)
            assertTrue("${slot.id} pivotU out of range", slot.pivotU in 0f..1f)
            assertTrue("${slot.id} pivotV out of range", slot.pivotV in 0f..1f)
        }

        for (i in slots.indices) {
            for (j in i + 1 until slots.size) {
                val a = slots[i]
                val b = slots[j]
                val overlapsX = a.u0 < b.u1 && b.u0 < a.u1
                val overlapsY = a.v0 < b.v1 && b.v0 < a.v1
                assertFalse("Atlas slots ${a.id} and ${b.id} overlap!", overlapsX && overlapsY)
            }
        }

        // Hit-test center of HEAD returns HEAD
        val head = AtlasPartSlot.HEAD
        assertEquals(head, AtlasPartSlot.hitTest((head.u0 + head.u1) / 2f, (head.v0 + head.v1) / 2f))
    }

    @Test
    fun `sprite atlas validator, JSON metadata, hand and blink keyframes, and maker routes work`() {
        val v2k = com.example.engine.SpriteAtlasEngine.validateAtlasDimensions(2048, 2048)
        assertTrue(v2k.isValid)
        assertTrue(v2k.isExactStandardSize)
        assertEquals(AtlasResolution.RES_2048, v2k.recommendedResolution)

        val v4k = com.example.engine.SpriteAtlasEngine.validateAtlasDimensions(4096, 4096)
        assertTrue(v4k.isValid)
        assertTrue(v4k.isExactStandardSize)
        assertEquals(AtlasResolution.RES_4096, v4k.recommendedResolution)

        val nonSquare = com.example.engine.SpriteAtlasEngine.validateAtlasDimensions(1920, 1080)
        assertFalse(nonSquare.isValid)

        // Keyframeable hand pose swap & blink override
        val clip = TimelineClip(
            layerId = "l1",
            name = "Atlas Actor",
            startFrame = 0,
            durationFrames = 40,
            handPose = HandPoseType.OPEN,
            keyframes = listOf(
                TransformKeyframe(frame = 0, handPoseOverride = HandPoseType.FIST, blinkOverride = true),
                TransformKeyframe(frame = 20, handPoseOverride = HandPoseType.POINT, blinkOverride = false)
            )
        )
        val at0 = KeyframeEvaluator.evaluate(clip, globalFrame = 0)
        assertEquals(HandPoseType.FIST, at0.handPoseOverride)
        assertEquals(true, at0.blinkOverride)
        val at20 = KeyframeEvaluator.evaluate(clip, globalFrame = 20)
        assertEquals(HandPoseType.POINT, at20.handPoseOverride)
        assertEquals(false, at20.blinkOverride)

        // CharacterRigDefinition with SpriteAtlasRigConfig JSON round-trip
        val atlasChar = CharacterRigDefinition(
            id = "char_atlas_1",
            name = "SuperHero",
            category = CharacterCategory.LATEST,
            isCustom = true,
            skeletonRig = SkeletonRigConfig(enabled = true, rigMode = CustomRigMode.ATLAS_RIG_PRO),
            spriteAtlasRig = SpriteAtlasRigConfig(
                poseTemplate = AtlasPoseTemplate.THREE_QUARTER_FRONT,
                resolutionPx = 4096,
                textureName = "SuperHero",
                placements = mapOf(
                    AtlasPartSlot.HEAD.id to AtlasPartPlacement(
                        slotId = AtlasPartSlot.HEAD.id,
                        partImagePath = "/tmp/head.png",
                        visible = true,
                        offsetX = 0.05f,
                        scale = 1.1f,
                        rotationDeg = -4f
                    )
                )
            )
        )
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(CharacterRigDefinition::class.java)
        val decoded = adapter.fromJson(adapter.toJson(atlasChar))
        assertNotNull(decoded)
        assertEquals(CustomRigMode.ATLAS_RIG_PRO, decoded?.skeletonRig?.rigMode)
        assertEquals(4096, decoded?.spriteAtlasRig?.resolutionPx)
        assertEquals(AtlasPoseTemplate.THREE_QUARTER_FRONT, decoded?.spriteAtlasRig?.poseTemplate)
        assertEquals(1.1f, decoded?.spriteAtlasRig?.placementFor(AtlasPartSlot.HEAD)?.scale ?: 0f, 0.001f)

        // HashRouter Character Setup & Maker routes
        val router = com.example.navigation.HashRouter()
        router.navigate(com.example.navigation.HashRoute.CharacterSetup)
        assertEquals(com.example.navigation.HashRoute.CharacterSetup, router.currentRoute)
        router.navigate(com.example.navigation.HashRoute.CharacterMaker("back_side", "char_atlas_1"))
        val makerRoute = router.currentRoute as com.example.navigation.HashRoute.CharacterMaker
        assertEquals("back_side", makerRoute.poseId)
        assertEquals("char_atlas_1", makerRoute.editCharacterId)
    }

    @Test
    fun `character code codec round-trips locked slots, SVG mode, and clip blackShade depthOrder`() {
        val originalConfig = SpriteAtlasRigConfig(
            poseTemplate = AtlasPoseTemplate.FRONT,
            resolutionPx = 4096,
            textureName = "VectorArjun",
            renderMode = "SVG",
            placements = mapOf(
                AtlasPartSlot.HEAD.id to AtlasPartPlacement(
                    slotId = AtlasPartSlot.HEAD.id,
                    partImagePath = "/storage/head.svg",
                    visible = true,
                    locked = true,
                    offsetX = 0.08f,
                    offsetY = -0.04f,
                    scale = 1.25f,
                    rotationDeg = 6f,
                    svgInline = "<circle cx=\"128\" cy=\"128\" r=\"96\" fill=\"#F5C6A5\"/>"
                )
            )
        )

        val code = CharacterCodeCodec.fromRigConfig(
            config = originalConfig,
            category = CharacterCategory.DESI,
            atlasFileName = "atlas.svg"
        )
        val json = CharacterCodeCodec.toJson(code)
        val parsed = CharacterCodeCodec.fromJson(json)
        assertNotNull(parsed)
        assertEquals("VectorArjun", parsed?.name)
        assertEquals("SVG", parsed?.renderMode)
        assertEquals(4096, parsed?.atlasSize)
        assertEquals(true, parsed?.slots?.get(AtlasPartSlot.HEAD.id)?.locked)

        val restoredConfig = CharacterCodeCodec.toRigConfig(
            code = parsed!!,
            resolvedAtlasPath = "/packs/atlas.svg"
        )
        assertTrue(restoredConfig.isSvgMode)
        assertTrue(restoredConfig.isSlotLocked(AtlasPartSlot.HEAD))
        assertEquals(1.25f, restoredConfig.placementFor(AtlasPartSlot.HEAD).scale, 0.001f)
        assertEquals(6f, restoredConfig.placementFor(AtlasPartSlot.HEAD).rotationDeg, 0.001f)

        // Verify TimelineClip blackShade + depthOrder round-trip through Moshi
        val clip = TimelineClip(
            layerId = "char_layer",
            name = "VectorArjun",
            startFrame = 0,
            durationFrames = 48,
            blackShade = 0.42f,
            depthOrder = 7
        )
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val clipAdapter = moshi.adapter(TimelineClip::class.java)
        val restoredClip = clipAdapter.fromJson(clipAdapter.toJson(clip))
        assertNotNull(restoredClip)
        assertEquals(0.42f, restoredClip?.blackShade ?: 0f, 0.001f)
        assertEquals(7, restoredClip?.depthOrder)
    }

    @Test
    fun `svg sanitizer blocks unsafe xml constructs and composes clean atlas svg`() {
        val validSvg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 2048 2048\"><circle cx=\"100\" cy=\"100\" r=\"50\"/></svg>"
        assertTrue(com.example.engine.SvgAtlasRenderer.isSafeSvgContent(validSvg))

        assertFalse(com.example.engine.SvgAtlasRenderer.isSafeSvgContent(""))
        assertFalse(com.example.engine.SvgAtlasRenderer.isSafeSvgContent("<html><body>not svg</body></html>"))
        assertFalse(
            com.example.engine.SvgAtlasRenderer.isSafeSvgContent(
                "<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><svg>&xxe;</svg>"
            )
        )
        assertFalse(
            com.example.engine.SvgAtlasRenderer.isSafeSvgContent(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
            )
        )

        val config = SpriteAtlasRigConfig(
            resolutionPx = 2048,
            textureName = "CleanHero",
            renderMode = "SVG",
            placements = mapOf(
                AtlasPartSlot.HEAD.id to AtlasPartPlacement(
                    slotId = AtlasPartSlot.HEAD.id,
                    visible = true,
                    svgInline = "<circle cx=\"128\" cy=\"128\" r=\"90\" fill=\"#FFCC99\"/>"
                )
            )
        )
        val composed = com.example.engine.SvgAtlasRenderer.composeCleanAtlasSvg(config, emptyMap())
        assertTrue(com.example.engine.SvgAtlasRenderer.isSafeSvgContent(composed))
        assertTrue(composed.contains("slot_head"))
        assertTrue(composed.contains("viewBox=\"0 0 2048 2048\""))
    }

    @Test
    fun `zip pack importer blocks zip slip traversal and extracts clean character pack`() {
        val tempRoot = java.nio.file.Files.createTempDirectory("fc_zip_test").toFile()
        try {
            // Path traversal checks
            assertNull(com.example.engine.CharacterPackImporter.resolveSafeZipEntryFile(tempRoot, "../evil.png"))
            assertNull(com.example.engine.CharacterPackImporter.resolveSafeZipEntryFile(tempRoot, "../../etc/passwd"))
            assertNull(com.example.engine.CharacterPackImporter.resolveSafeZipEntryFile(tempRoot, "parts/../../escape.svg"))
            val safeNested = com.example.engine.CharacterPackImporter.resolveSafeZipEntryFile(tempRoot, "parts/head.svg")
            assertNotNull(safeNested)
            assertTrue(safeNested!!.canonicalPath.startsWith(tempRoot.canonicalPath))

            // In-memory malicious ZIP with Zip Slip entry fails cleanly
            val evilBytes = java.io.ByteArrayOutputStream().apply {
                java.util.zip.ZipOutputStream(this).use { zos ->
                    zos.putNextEntry(java.util.zip.ZipEntry("../escape.png"))
                    zos.write(byteArrayOf(1, 2, 3))
                    zos.closeEntry()
                }
            }.toByteArray()
            val evilDir = java.io.File(tempRoot, "evil_out")
            val evilResult = com.example.engine.CharacterPackImporter.extractZipSafely(
                java.io.ByteArrayInputStream(evilBytes),
                evilDir
            )
            assertTrue(evilResult.isFailure)

            // In-memory valid ZIP extracts .fcchar.json + atlas.svg and skips disallowed extensions
            val goodBytes = java.io.ByteArrayOutputStream().apply {
                java.util.zip.ZipOutputStream(this).use { zos ->
                    zos.putNextEntry(java.util.zip.ZipEntry("character.fcchar.json"))
                    zos.write("""{"schemaVersion":1,"name":"PackHero","renderMode":"SVG","atlasSize":2048}""".toByteArray())
                    zos.closeEntry()

                    zos.putNextEntry(java.util.zip.ZipEntry("atlas.svg"))
                    zos.write("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 2048 2048"><rect width="100" height="100"/></svg>""".toByteArray())
                    zos.closeEntry()

                    zos.putNextEntry(java.util.zip.ZipEntry("ignored.exe"))
                    zos.write(byteArrayOf(0x4D, 0x5A))
                    zos.closeEntry()
                }
            }.toByteArray()
            val goodDir = java.io.File(tempRoot, "good_out")
            val goodResult = com.example.engine.CharacterPackImporter.extractZipSafely(
                java.io.ByteArrayInputStream(goodBytes),
                goodDir
            )
            assertTrue(goodResult.isSuccess)
            val extractedNames = goodResult.getOrThrow().map { it.name }.toSet()
            assertEquals(setOf("character.fcchar.json", "atlas.svg"), extractedNames)
            assertFalse(java.io.File(goodDir, "ignored.exe").exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `audio waveform extractor parses PCM WAV and aligns sound peaks to timeline frames`() {
        // 1. Direct PCM-16 extraction: silence in first half, loud burst in third quarter
        val samples = ShortArray(400) { i ->
            when {
                i in 200 until 300 -> if (i % 2 == 0) 28000 else -28000
                else -> 0
            }
        }
        val peaks = com.example.engine.AudioWaveformExtractor.extractPeaksFromPcm16(
            samples = samples,
            channels = 1,
            bucketCount = 4
        )
        assertEquals(4, peaks.size)
        assertEquals(0f, peaks[0], 0.001f)
        assertEquals(0f, peaks[1], 0.001f)
        assertTrue("Expected loud peak in bucket 2, got ${peaks[2]}", peaks[2] > 0.9f)
        assertEquals(0f, peaks[3], 0.001f)

        // 2. Real 2-second 16-bit mono WAV file on disk: 1s silence + 1s loud speech burst
        val tempWav = java.nio.file.Files.createTempFile("fc_wave_test", ".wav").toFile()
        try {
            val sampleRate = 8000
            val totalSamples = sampleRate * 2 // 2000ms
            val dataBytes = totalSamples * 2
            val bb = java.nio.ByteBuffer.allocate(44 + dataBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray(Charsets.US_ASCII))
            bb.putInt(36 + dataBytes)
            bb.put("WAVE".toByteArray(Charsets.US_ASCII))
            bb.put("fmt ".toByteArray(Charsets.US_ASCII))
            bb.putInt(16) // PCM chunk size
            bb.putShort(1) // PCM format
            bb.putShort(1) // mono
            bb.putInt(sampleRate)
            bb.putInt(sampleRate * 2) // byteRate
            bb.putShort(2) // blockAlign
            bb.putShort(16) // bitsPerSample
            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(dataBytes)
            for (i in 0 until totalSamples) {
                val s: Short = if (i >= sampleRate) {
                    if (i % 4 < 2) 26000 else -26000
                } else {
                    0
                }
                bb.putShort(s)
            }
            tempWav.writeBytes(bb.array())

            val parsed = com.example.engine.AudioWaveformExtractor.parseWavWaveform(tempWav, targetBuckets = 40)
            assertNotNull(parsed)
            assertEquals(2000L, parsed!!.durationMs)
            assertEquals(40, parsed.amplitudes.size)

            // At 24fps: frame 6 (250ms) is in the silent first second
            val silentAmp = com.example.engine.AudioWaveformExtractor.samplePeakInFrameRange(
                waveform = parsed,
                startLocalFrame = 6f,
                endLocalFrame = 7f,
                clipDurationFrames = 96,
                fps = 24,
                speedMultiplier = 1f
            )
            assertEquals(0f, silentAmp, 0.01f)

            // At 24fps: frame 36 (1500ms) is inside the loud second half
            val loudAmp = com.example.engine.AudioWaveformExtractor.samplePeakInFrameRange(
                waveform = parsed,
                startLocalFrame = 35f,
                endLocalFrame = 37f,
                clipDurationFrames = 96,
                fps = 24,
                speedMultiplier = 1f
            )
            assertTrue("Expected loud peak at 1500ms, got $loudAmp", loudAmp > 0.85f)

            // At 24fps: frame 60 (2500ms) is past the 2000ms audio duration -> returns -1f sentinel
            val pastEndAmp = com.example.engine.AudioWaveformExtractor.samplePeakInFrameRange(
                waveform = parsed,
                startLocalFrame = 60f,
                endLocalFrame = 61f,
                clipDurationFrames = 96,
                fps = 24,
                speedMultiplier = 1f
            )
            assertEquals(-1f, pastEndAmp, 0.001f)
        } finally {
            tempWav.delete()
        }
    }

    @Test
    fun `audio trimming updates waveform sampling and exports lightweight lip-sync JSON cleanly`() {
        // 2-second waveform: 0..1000ms silent (0f), 1000..1500ms loud speech (0.9f), 1500..2000ms quiet (0.05f)
        val amps = FloatArray(40) { idx ->
            when {
                idx < 20 -> 0f
                idx < 30 -> 0.9f
                else -> 0.05f
            }
        }
        val wf = com.example.engine.AudioWaveformData(amplitudes = amps, durationMs = 2000L)

        // Trim clip to 1000ms..1500ms (12 frames @ 24fps): local frame 0 now reads 1000ms loud peak!
        val trimmedStartPeak = com.example.engine.AudioWaveformExtractor.samplePeakInFrameRange(
            waveform = wf,
            startLocalFrame = 0f,
            endLocalFrame = 1f,
            clipDurationFrames = 12,
            fps = 24,
            speedMultiplier = 1f,
            trimStartMs = 1000L,
            trimEndMs = 1500L
        )
        assertTrue("Expected loud peak at trimmed start, got $trimmedStartPeak", trimmedStartPeak > 0.85f)

        // Past trimmed end (frame 13 -> 1541ms > 1500ms) returns -1f sentinel
        val pastTrimEnd = com.example.engine.AudioWaveformExtractor.samplePeakInFrameRange(
            waveform = wf,
            startLocalFrame = 13f,
            endLocalFrame = 14f,
            clipDurationFrames = 24,
            fps = 24,
            speedMultiplier = 1f,
            trimStartMs = 1000L,
            trimEndMs = 1500L
        )
        assertEquals(-1f, pastTrimEnd, 0.001f)

        // ProjectMutator.trimAudioClip + resetAudioTrim + WaveformLipSyncExport JSON round-trip
        val baseProject = ProjectDocument(
            name = "LipSync Test",
            fps = 24,
            scenes = listOf(
                AnimationScene(
                    name = "Scene 1",
                    durationFrames = 96,
                    layers = listOf(
                        AnimationLayer(id = "char_layer", name = "Hero", type = LayerType.CHARACTER, orderIndex = 1)
                    ),
                    clips = listOf(
                        TimelineClip(
                            id = "char_1",
                            layerId = "char_layer",
                            name = "Arjun",
                            characterId = "hd_arjun_hero",
                            startFrame = 0,
                            durationFrames = 96,
                            facialExpression = FacialExpression.TALKING,
                            keyframes = listOf(
                                TransformKeyframe(frame = 12, expressionOverride = FacialExpression.SHOCKED, blinkOverride = true)
                            )
                        )
                    )
                )
            )
        )
        val (withAudio, audioClipId) = com.example.viewmodel.ProjectMutator.addAudioClip(
            project = baseProject,
            name = "Voiceover",
            audioPath = "/tmp/voice.wav",
            startFrame = 0
        )
        val trimmedProject = com.example.viewmodel.ProjectMutator.trimAudioClip(
            project = withAudio,
            clipId = audioClipId,
            newStartFrame = 12,
            newDurationFrames = 24,
            newTrimStartMs = 1000L,
            newTrimEndMs = 1500L
        )
        val trimmedClip = trimmedProject.activeScene.clips.first { it.id == audioClipId }
        assertEquals(12, trimmedClip.startFrame)
        assertEquals(24, trimmedClip.durationFrames)
        assertEquals(1000L, trimmedClip.audioTrimStartMs)
        assertEquals(1500L, trimmedClip.audioTrimEndMs)

        // Export lightweight waveform + lip-sync JSON
        val exportModel = com.example.engine.AudioWaveformJsonCodec.buildExport(
            project = trimmedProject,
            scene = trimmedProject.activeScene,
            audioClip = trimmedClip,
            waveform = wf,
            maxExportPeaks = 32
        )
        assertEquals(32, exportModel.peakCount)
        assertEquals(1000L, exportModel.trimStartMs)
        assertEquals(1500L, exportModel.trimEndMs)
        assertTrue("Expected MTH4 mouth cue on 0.9 peak", exportModel.lipSyncCues.any { it.mouthShape == "MTH4" })
        assertEquals(1, exportModel.syncedCharacters.size)
        assertEquals("SHOCKED", exportModel.syncedCharacters.first().expressionKeyframes.first().expression)

        val compactJson = com.example.engine.AudioWaveformJsonCodec.toJson(exportModel, pretty = false)
        val restored = com.example.engine.AudioWaveformJsonCodec.fromJson(compactJson)
        assertNotNull(restored)
        assertEquals(exportModel.clipName, restored?.clipName)
        assertEquals(exportModel.trimStartMs, restored?.trimStartMs)
        assertEquals(exportModel.trimEndMs, restored?.trimEndMs)
        assertEquals(exportModel.peaks.size, restored?.peaks?.size)
        assertNull(com.example.engine.AudioWaveformJsonCodec.fromJson("""{"schemaVersion":99,"format":"bad"}"""))

        // Reset audio trim restores untrimmed bounds
        val resetProject = com.example.viewmodel.ProjectMutator.resetAudioTrim(
            project = trimmedProject,
            clipId = audioClipId,
            fullAudioDurationMs = 2000L
        )
        val resetClip = resetProject.activeScene.clips.first { it.id == audioClipId }
        assertEquals(0L, resetClip.audioTrimStartMs)
        assertEquals(0L, resetClip.audioTrimEndMs)
        assertEquals(48, resetClip.durationFrames)
    }

    @Test
    fun `svg atlas composer resolves dynamic viewBox and preserveAspectRatio without hardcoding 256`() {
        val headSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><circle cx="256" cy="256" r="200" fill="#F5C6A5"/></svg>"""
        val chestSvg = """<svg xmlns="http://www.w3.org/2000/svg" width="300px" height="400px"><rect width="300" height="400" fill="#38BDF8"/></svg>"""

        assertEquals("0 0 512 512", com.example.engine.SvgAtlasRenderer.extractSvgViewBox(headSvg))
        assertEquals("0 0 300 400", com.example.engine.SvgAtlasRenderer.extractSvgViewBox(chestSvg))

        val config = SpriteAtlasRigConfig(
            resolutionPx = 2048,
            textureName = "DynamicSvgHero",
            renderMode = "SVG",
            faceBakedInHead = true
        )
        val composed = com.example.engine.SvgAtlasRenderer.composeCleanAtlasSvg(
            config = config,
            partSvgStringsBySlot = mapOf(
                AtlasPartSlot.HEAD to headSvg,
                AtlasPartSlot.CHEST_BODY to chestSvg
            )
        )
        assertTrue("Expected dynamic 512 viewBox on HEAD slot", composed.contains("""viewBox="0 0 512 512" preserveAspectRatio="xMidYMid meet""""))
        assertTrue("Expected derived 300x400 viewBox on CHEST_BODY slot", composed.contains("""viewBox="0 0 300 400" preserveAspectRatio="xMidYMid meet""""))

        // Verify faceBakedInHead persists through CharacterCodeCodec JSON round-trip
        val code = CharacterCodeCodec.fromRigConfig(config)
        val parsed = CharacterCodeCodec.fromJson(CharacterCodeCodec.toJson(code))
        assertNotNull(parsed)
        assertTrue(parsed!!.faceBakedInHead)
        assertTrue(CharacterCodeCodec.toRigConfig(parsed).faceBakedInHead)
    }

    @Test
    fun `character importer resolves clean unique names and never defaults to Arjun HD`() {
        assertEquals("Rohan Ninja", com.example.engine.CharacterPackImporter.cleanFileBasename("Rohan_Ninja.fcchar.json"))
        assertEquals("Cyber Girl", com.example.engine.CharacterPackImporter.cleanFileBasename("Cyber-Girl.svg"))

        val fromFile = com.example.engine.CharacterPackImporter.resolveImportedCharacterName(
            jsonName = "CustomHero",
            fallbackFileName = "village_elder_v2.svg"
        )
        assertEquals("village elder v2", fromFile)
        assertNotEquals("Arjun HD (SVG)", fromFile)

        val unique = com.example.engine.CharacterPackImporter.ensureUniqueCharacterName(
            baseName = "Arjun HD (SVG)",
            existingNames = listOf("Arjun HD (SVG)", "Maya HD (SVG)", "Arjun HD (SVG) (2)")
        )
        assertEquals("Arjun HD (SVG) (3)", unique)
    }

    @Test
    fun `project audioSyncTracks schema round-trips phoneme mappings, volume curve, and waveform sensitivity`() {
        // Quiet 0.25f speech burst -> at 1.0x sensitivity maps to EE/MTH2, at 2.8x sensitivity boosts to 0.70f (AA/MTH4)
        val amps = FloatArray(48) { idx -> if (idx in 12..35) 0.25f else 0.02f }
        val wf = com.example.engine.AudioWaveformData(amplitudes = amps, durationMs = 2000L)

        assertEquals(0.25f, com.example.engine.AudioWaveformJsonCodec.scaleAmplitude(0.25f, 1.0f), 0.001f)
        assertEquals(0.70f, com.example.engine.AudioWaveformJsonCodec.scaleAmplitude(0.25f, 2.8f), 0.001f)
        assertEquals(1.0f, com.example.engine.AudioWaveformJsonCodec.scaleAmplitude(0.6f, 3.0f), 0.001f)
        assertEquals(-1f, com.example.engine.AudioWaveformJsonCodec.scaleAmplitude(-1f, 2.5f), 0.001f)

        val audioClip = TimelineClip(
            id = "aud_sync_1",
            layerId = "audio_layer",
            name = "Whisper Dialogue",
            audioPath = "/tmp/whisper.wav",
            startFrame = 0,
            durationFrames = 48
        )
        val baseProject = ProjectDocument(name = "Sync Schema Test", fps = 24)
        val syncMeta = com.example.engine.AudioWaveformJsonCodec.buildAudioSyncMetadata(
            project = baseProject,
            audioClip = audioClip,
            waveform = wf,
            sensitivity = 2.8f,
            volumeCurveSamples = 16
        )

        assertEquals("aud_sync_1", syncMeta.clipId)
        assertEquals(2.8f, syncMeta.waveformSensitivity, 0.001f)
        assertEquals(16, syncMeta.volumeCurve.size)
        assertTrue("Expected boosted phoneme mappings on quiet speech", syncMeta.phonemeMappings.any { it.phoneme == "AA" || it.phoneme == "OH" })
        assertTrue("Expected volume curve timestamps to increase over duration", syncMeta.volumeCurve.last().timeMs > syncMeta.volumeCurve.first().timeMs)

        // Verify full ProjectDocument Moshi round-trip with audioSyncTracks
        val projectWithSync = baseProject.copy(audioSyncTracks = listOf(syncMeta))
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ProjectDocument::class.java)
        val restored = adapter.fromJson(adapter.toJson(projectWithSync))
        assertNotNull(restored)
        assertEquals(1, restored!!.audioSyncTracks.size)
        assertEquals(2.8f, restored.audioSyncTracks.first().waveformSensitivity, 0.001f)
        assertEquals(16, restored.audioSyncTracks.first().volumeCurve.size)
        assertEquals(syncMeta.phonemeMappings.size, restored.audioSyncTracks.first().phonemeMappings.size)
    }

    @Test
    fun `organic facial expression engine produces overshoot, staggered brow-lead, asymmetric wink, and living micro-saccades`() {
        // 1. Overshoot curve should exceed 1.0 slightly mid-transition and settle back to 1.0
        val peakSample = (1..20).maxOf { i ->
            com.example.engine.FacialExpressionEngine.organicOvershootCurve(i / 20f, overshoot = 0.35f)
        }
        assertTrue("Expected elastic overshoot > 1.0, got $peakSample", peakSample > 1.02f)
        assertEquals(1f, com.example.engine.FacialExpressionEngine.organicOvershootCurve(1f, 0.35f), 0.0001f)

        // 2. Staggered overlap: early in transition from Neutral -> Shocked, brows lead ahead of mouth
        val earlyTransition = com.example.engine.FacialExpressionEngine.evaluateOrganicFace(
            activePresetId = com.example.engine.FacialExpressionEngine.PRESET_SHOCKED,
            previousPresetId = com.example.engine.FacialExpressionEngine.PRESET_NEUTRAL,
            fallbackExpression = FacialExpression.SHOCKED,
            framesSinceKey = 1,
            globalFrame = 10,
            fps = 24
        )
        assertEquals(AtlasPartSlot.BROW_SCARED, earlyTransition.resolvedBrowSlot)
        assertTrue("Expected brows already lifting early in shock transition", earlyTransition.leftBrowLift > 0.3f)

        // 3. Settled Angry & Happy map automatically to matching mouth/brow atlas slots
        val angry = com.example.engine.FacialExpressionEngine.evaluateOrganicFace(
            activePresetId = com.example.engine.FacialExpressionEngine.PRESET_ANGRY,
            previousPresetId = com.example.engine.FacialExpressionEngine.PRESET_ANGRY,
            fallbackExpression = FacialExpression.ANGRY,
            framesSinceKey = 24,
            globalFrame = 40,
            fps = 24
        )
        assertEquals(AtlasPartSlot.BROW_ANGRY, angry.resolvedBrowSlot)
        assertEquals(AtlasPartSlot.LIP_ANGRY, angry.resolvedMouthSlot)
        assertTrue(angry.showGrittedTeeth)

        val happy = com.example.engine.FacialExpressionEngine.evaluateOrganicFace(
            activePresetId = com.example.engine.FacialExpressionEngine.PRESET_HAPPY,
            previousPresetId = com.example.engine.FacialExpressionEngine.PRESET_HAPPY,
            fallbackExpression = FacialExpression.HAPPY,
            framesSinceKey = 24,
            globalFrame = 40,
            fps = 24
        )
        assertEquals(AtlasPartSlot.BROW_HAPPY, happy.resolvedBrowSlot)
        assertEquals(AtlasPartSlot.LIP_HAPPY, happy.resolvedMouthSlot)
        assertTrue(happy.smileCurve > 0.6f)

        // 4. Playful Wink is genuinely asymmetric (right eye closed, left eye open)
        val wink = com.example.engine.FacialExpressionEngine.evaluateOrganicFace(
            activePresetId = com.example.engine.FacialExpressionEngine.PRESET_WINK,
            previousPresetId = com.example.engine.FacialExpressionEngine.PRESET_WINK,
            fallbackExpression = FacialExpression.HAPPY,
            framesSinceKey = 24,
            globalFrame = 5,
            fps = 24,
            blinkOverride = null
        )
        assertTrue("Right eye should close for wink", wink.rightEyelidClose > 0.85f)
        assertTrue("Left eye should stay noticeably more open than right eye", wink.leftEyeOpenness > wink.rightEyeOpenness + 0.5f)

        // 5. Micro-saccades keep gaze organically alive across frames on a held Neutral expression
        val gazeSamples = (0..72 step 12).map { f ->
            com.example.engine.FacialExpressionEngine.evaluateOrganicFace(
                activePresetId = com.example.engine.FacialExpressionEngine.PRESET_NEUTRAL,
                previousPresetId = com.example.engine.FacialExpressionEngine.PRESET_NEUTRAL,
                fallbackExpression = FacialExpression.NEUTRAL,
                framesSinceKey = 60,
                globalFrame = f,
                fps = 24
            ).eyeLookX
        }
        assertTrue("Expected living micro-saccade gaze variation across frames", gazeSamples.max() - gazeSamples.min() > 0.05f)
    }

    @Test
    fun `dropping facial expression preset onto character layer inserts keyframe and tracks transitions across transform keys`() {
        val base = BuiltInCatalog.sceneTemplates.first().buildProject("Facial Drop Test")
        val charLayer = base.activeScene.layers.first { it.type == LayerType.CHARACTER }
        val charClip = base.activeScene.clips.first { it.layerId == charLayer.id && it.characterId != null }

        // Drop Happy at frame 12 and Angry at frame 30
        val (afterHappy, clipId1) = ProjectMutator.dropFacialExpressionPreset(
            project = base,
            layerId = charLayer.id,
            clipId = charClip.id,
            globalFrame = charClip.startFrame + 12,
            presetId = com.example.engine.FacialExpressionEngine.PRESET_HAPPY
        )
        assertEquals(charClip.id, clipId1)

        // Add a transform-only keyframe in between at frame 20 (should NOT erase Happy expression)
        val afterMove = ProjectMutator.upsertKeyframeAtFrame(
            project = afterHappy,
            clipId = charClip.id,
            globalFrame = charClip.startFrame + 20
        ) { it.copy(x = 0.62f) }

        val (afterAngry, clipId2) = ProjectMutator.dropFacialExpressionPreset(
            project = afterMove,
            layerId = charLayer.id,
            clipId = charClip.id,
            globalFrame = charClip.startFrame + 30,
            presetId = com.example.engine.FacialExpressionEngine.PRESET_ANGRY
        )
        assertEquals(charClip.id, clipId2)

        val updatedClip = afterAngry.activeScene.clips.first { it.id == charClip.id }

        // At frame 24 (after movement keyframe), Happy is still active
        val evalAt24 = KeyframeEvaluator.evaluate(updatedClip, charClip.startFrame + 24)
        assertEquals(com.example.engine.FacialExpressionEngine.PRESET_HAPPY, evalAt24.facialPresetId)

        // At frame 33 (3 frames into Angry), active = Angry, previous = Happy, framesSinceFacialKey = 3
        val evalAt33 = KeyframeEvaluator.evaluate(updatedClip, charClip.startFrame + 33)
        assertEquals(com.example.engine.FacialExpressionEngine.PRESET_ANGRY, evalAt33.facialPresetId)
        assertEquals(com.example.engine.FacialExpressionEngine.PRESET_HAPPY, evalAt33.previousFacialPresetId)
        assertEquals(3, evalAt33.framesSinceFacialKey)

        // Dropping onto a non-character layer is safely rejected
        val bgLayer = afterAngry.activeScene.layers.firstOrNull { it.type != LayerType.CHARACTER }
        if (bgLayer != null) {
            val (_, rejectedClip) = ProjectMutator.dropFacialExpressionPreset(
                project = afterAngry,
                layerId = bgLayer.id,
                clipId = null,
                globalFrame = 10,
                presetId = com.example.engine.FacialExpressionEngine.PRESET_SAD
            )
            assertNull(rejectedClip)
        }

        // Verify Moshi JSON round-trip preserves facialPresetId
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ProjectDocument::class.java)
        val restored = adapter.fromJson(adapter.toJson(afterAngry))
        assertNotNull(restored)
        val restoredClip = restored!!.activeScene.clips.first { it.id == charClip.id }
        assertTrue(restoredClip.keyframes.any { it.facialPresetId == com.example.engine.FacialExpressionEngine.PRESET_ANGRY })
    }

    @Test
    fun `advanced easing curves evaluate accurately including Bounce, Elastic overshoot, and Custom Cubic Bezier`() {
        for (easing in EasingType.entries) {
            assertEquals("Start value for $easing", 0f, KeyframeEvaluator.evaluateEasing(easing, 0f), 0.001f)
            assertEquals("End value for $easing", 1f, KeyframeEvaluator.evaluateEasing(easing, 1f, isAtEnd = true), 0.001f)
        }
        // Ease-In starts slower than linear; Ease-Out starts faster than linear
        val easeInMid = KeyframeEvaluator.evaluateEasing(EasingType.EASE_IN, 0.5f)
        val easeOutMid = KeyframeEvaluator.evaluateEasing(EasingType.EASE_OUT, 0.5f)
        assertTrue("Ease-In at 0.5 ($easeInMid) should be < 0.5", easeInMid < 0.3f)
        assertTrue("Ease-Out at 0.5 ($easeOutMid) should be > 0.5", easeOutMid > 0.7f)

        // Elastic overshoots above 1.0 before settling cleanly to 1.0
        val elasticSamples = (1..20).map { KeyframeEvaluator.evaluateEasing(EasingType.ELASTIC, it / 20f) }
        assertTrue("Elastic curve should overshoot > 1.0", elasticSamples.any { it > 1.05f })

        // Bounce rebounds cleanly within 0..1.01
        val bounceMid = KeyframeEvaluator.evaluateEasing(EasingType.BOUNCE, 0.55f)
        assertTrue("Bounce curve should be in (0.5..1.01), got $bounceMid", bounceMid in 0.5f..1.01f)

        // Custom Cubic Bézier with overshoot control point produces y > 1.0 in mid-travel
        val cubicOvershoot = KeyframeEvaluator.evaluateEasing(
            easing = EasingType.CUSTOM_CUBIC,
            rawT = 0.65f,
            p1x = 0.25f,
            p1y = 1.45f,
            p2x = 0.60f,
            p2y = 1.25f
        )
        assertTrue("Custom cubic with overshoot handles should exceed 1.0, got $cubicOvershoot", cubicOvershoot > 1.0f)
    }

    @Test
    fun `scene VFX config, deterministic camera shake, and two VFX starter templates work cleanly`() {
        // Deterministic camera shake returns identical offsets for identical (frame, fps, intensity, seed)
        val s1 = com.example.engine.SceneVfxEngine.computeCameraShake(frame = 17, fps = 24, intensity = 0.6f, seed = 99)
        val s2 = com.example.engine.SceneVfxEngine.computeCameraShake(frame = 17, fps = 24, intensity = 0.6f, seed = 99)
        assertEquals(s1, s2)
        assertFalse("Active shake must be non-zero", s1.isZero)

        val zeroShake = com.example.engine.SceneVfxEngine.computeCameraShake(frame = 17, fps = 24, intensity = 0f, seed = 99)
        assertTrue(zeroShake.isZero)

        // All 7 particle types + NONE and 5 lighting moods + NONE exist
        assertEquals(8, SceneParticleType.entries.size)
        assertEquals(6, SceneLightingMood.entries.size)

        // Verify two new VFX starter templates exist AND tpl_blank_stage remains LAST for resetCurrentProject()
        val templates = BuiltInCatalog.sceneTemplates
        assertEquals("tpl_blank_stage", templates.last().id)
        val neonTpl = templates.firstOrNull { it.id == "tpl_vfx_neon_rain" }
        val fireflyTpl = templates.firstOrNull { it.id == "tpl_vfx_firefly_grove" }
        assertNotNull(neonTpl)
        assertNotNull(fireflyTpl)

        val neonProj = neonTpl!!.buildProject("Neon Test")
        assertEquals(SceneParticleType.RAIN, neonProj.activeScene.vfxConfig.particleType)
        assertEquals(SceneLightingMood.NEON_CYBER, neonProj.activeScene.vfxConfig.lightingMood)
        assertTrue(neonProj.activeScene.vfxConfig.vignetteIntensity > 0.3f)

        val fireflyProj = fireflyTpl!!.buildProject("Grove Test")
        assertEquals(SceneParticleType.FIREFLIES, fireflyProj.activeScene.vfxConfig.particleType)
        assertEquals(SceneLightingMood.GOLDEN_HOUR, fireflyProj.activeScene.vfxConfig.lightingMood)

        // Verify Moshi JSON round-trip of SceneVfxConfig
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ProjectDocument::class.java)
        val restored = adapter.fromJson(adapter.toJson(neonProj))
        assertEquals(neonProj.activeScene.vfxConfig, restored?.activeScene?.vfxConfig)
    }

    @Test
    fun `5 new character animations, comic bubbles, neon brush undo-clear, quick motion, camera presets, and scene duplication work`() {
        // 5 new character animations produce valid distinct poses
        val newPresets = listOf(
            AnimationPresetType.FIGHTING,
            AnimationPresetType.FLYING,
            AnimationPresetType.SLEEPING,
            AnimationPresetType.CLAPPING,
            AnimationPresetType.THINKING
        )
        val poses = newPresets.map { ProceduralRigRenderer.computePose(it, 0.25f) }
        assertEquals("All 5 new animations must produce distinct poses", 5, poses.toSet().size)
        assertTrue("Sleeping pose closes eyes", ProceduralRigRenderer.computePose(AnimationPresetType.SLEEPING, 0.3f).blinkClosed)
        assertTrue("Flying pose elevates body", ProceduralRigRenderer.computePose(AnimationPresetType.FLYING, 0.25f).bodyBobY < -10f)
        for (preset in newPresets) {
            assertTrue("BuiltInCatalog missing $preset", BuiltInCatalog.animationPresets.any { it.type == preset })
        }

        // Comic text bubble creation & persistence
        var proj = BuiltInCatalog.sceneTemplates.first().buildProject("Studio Test")
        val (withBubble, bubbleId) = ProjectMutator.addTextClip(
            project = proj,
            text = "BOOM!",
            colorHex = "#0F172A",
            sizeSp = 30f,
            startFrame = 10,
            bubbleStyle = TextBubbleStyle.SHOUT,
            bubbleFillHex = "#FDE047"
        )
        proj = withBubble
        val bubbleClip = proj.activeScene.clips.first { it.id == bubbleId }
        assertEquals(TextBubbleStyle.SHOUT, bubbleClip.textBubbleStyle)
        assertEquals("#FDE047", bubbleClip.textBubbleFillHex)

        // Quick Motion preset stamps eased keyframes
        val targetChar = proj.activeScene.clips.first { it.characterId != null }
        val withMotion = ProjectMutator.applyQuickMotionPreset(proj, targetChar.id, 0, "bounce_in")
        assertTrue(withMotion.activeScene.clips.first { it.id == targetChar.id }.keyframes.any { it.easing == EasingType.BOUNCE })

        // 1-Tap Cinematic Camera Preset stamps camera keyframes
        val withCam = ProjectMutator.applyCinematicCameraPreset(withMotion, 0, "crash_zoom")
        assertTrue(withCam.activeScene.cameraKeyframes.size >= 2)
        assertTrue(withCam.activeScene.cameraKeyframes.any { it.easing == EasingType.ELASTIC })

        // Neon Glow & Sketch Dashed brush strokes + per-stroke undo & clear
        val neonStroke = DrawingStroke(
            points = listOf(NormalizedPoint(0.2f, 0.3f), NormalizedPoint(0.5f, 0.5f)),
            colorHex = "#38BDF8",
            brushStyle = BrushStyle.NEON_GLOW
        )
        val sketchStroke = DrawingStroke(
            points = listOf(NormalizedPoint(0.3f, 0.6f), NormalizedPoint(0.7f, 0.6f)),
            colorHex = "#F59E0B",
            brushStyle = BrushStyle.SKETCH_DASHED
        )
        val (afterStroke1, drawClipId) = ProjectMutator.addDrawingStroke(withCam, neonStroke, 0)
        val (afterStroke2, _) = ProjectMutator.addDrawingStroke(afterStroke1, sketchStroke, 0)
        assertEquals(2, afterStroke2.activeScene.clips.first { it.id == drawClipId }.drawingStrokes.size)

        val (afterUndo, didUndo) = ProjectMutator.undoLastDrawingStroke(afterStroke2, 0)
        assertTrue(didUndo)
        val remainingStrokes = afterUndo.activeScene.clips.first { it.id == drawClipId }.drawingStrokes
        assertEquals(1, remainingStrokes.size)
        assertEquals(BrushStyle.NEON_GLOW, remainingStrokes.first().brushStyle)

        val (afterClear, didClear) = ProjectMutator.clearDrawingStrokes(afterUndo, 0)
        assertTrue(didClear)
        assertTrue(afterClear.activeScene.clips.first { it.id == drawClipId }.drawingStrokes.isEmpty())

        // Scene duplication deep-clones scene, layers, and clips with fresh UUIDs and remapped layerIds
        val origScene = afterClear.activeScene
        val duplicated = ProjectMutator.duplicateActiveScene(afterClear)
        assertEquals(afterClear.scenes.size + 1, duplicated.scenes.size)
        val dupScene = duplicated.activeScene
        assertNotEquals(origScene.id, dupScene.id)
        assertEquals(origScene.layers.size, dupScene.layers.size)
        assertEquals(origScene.clips.size, dupScene.clips.size)
        val dupLayerIds = dupScene.layers.map { it.id }.toSet()
        assertTrue("All duplicated clips must map to duplicated layer IDs", dupScene.clips.all { it.layerId in dupLayerIds })
        assertTrue("Duplicated layer IDs must be distinct from originals", dupLayerIds.intersect(origScene.layers.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun `prop sway physics engine computes deterministic spring pendulum motion, live drag kick, and respects toggle and strength`() {
        val movingClip = TimelineClip(
            id = "clip_lantern",
            layerId = "char_layer",
            name = "Hero Lantern Walk",
            characterId = "char_aarav_desi",
            startFrame = 0,
            durationFrames = 60,
            animationPresetType = AnimationPresetType.WALK,
            propType = CharacterPropType.LANTERN,
            propAttachment = PropAttachmentPoint.RIGHT_HAND,
            propSwayEnabled = true,
            propSwayStrength = 1.0f,
            keyframes = listOf(
                TransformKeyframe(frame = 0, x = 0.2f, y = 0.65f, easing = EasingType.EASE_IN_OUT),
                TransformKeyframe(frame = 30, x = 0.8f, y = 0.65f, easing = EasingType.EASE_IN_OUT)
            )
        )
        val rig = BuiltInCatalog.builtInCharacters.first()

        // Deterministic evaluation at same frame
        val s1 = com.example.engine.PropSwayPhysicsEngine.evaluatePropSway(movingClip, rig, frame = 15, fps = 24)
        val s2 = com.example.engine.PropSwayPhysicsEngine.evaluatePropSway(movingClip, rig, frame = 15, fps = 24)
        assertEquals(s1, s2)
        assertTrue("Running lantern should have noticeable sway angle", kotlin.math.abs(s1.angleDeg) > 1.5f)
        assertTrue("Running character should have accessory flutter", kotlin.math.abs(s1.accessoryFlutterDeg) > 0.5f)

        // Disabled sway returns zero state
        val disabled = com.example.engine.PropSwayPhysicsEngine.evaluatePropSway(
            movingClip.copy(propSwayEnabled = false), rig, frame = 15, fps = 24
        )
        assertEquals(0f, disabled.angleDeg, 0.0001f)
        assertEquals(0f, disabled.offsetX, 0.0001f)
        assertEquals(0f, disabled.accessoryFlutterDeg, 0.0001f)

        // Strength scaling amplifies response
        val boosted = com.example.engine.PropSwayPhysicsEngine.evaluatePropSway(
            movingClip.copy(propSwayStrength = 1.8f), rig, frame = 15, fps = 24
        )
        assertTrue("Higher sway strength should increase sway magnitude", kotlin.math.abs(boosted.angleDeg) > kotlin.math.abs(s1.angleDeg))

        // Live drag velocity produces immediate directional sway reaction
        val dragKickRight = com.example.engine.PropSwayPhysicsEngine.evaluatePropSway(
            clip = movingClip.copy(keyframes = listOf(TransformKeyframe(frame = 0, x = 0.5f, y = 0.65f)), animationPresetType = AnimationPresetType.IDLE),
            rig = rig,
            frame = 0,
            fps = 24,
            gesturePreview = com.example.engine.StageGesturePreview(draggedClipId = movingClip.id, dragVelocityX = 0.85f)
        )
        val dragKickLeft = com.example.engine.PropSwayPhysicsEngine.evaluatePropSway(
            clip = movingClip.copy(keyframes = listOf(TransformKeyframe(frame = 0, x = 0.5f, y = 0.65f)), animationPresetType = AnimationPresetType.IDLE),
            rig = rig,
            frame = 0,
            fps = 24,
            gesturePreview = com.example.engine.StageGesturePreview(draggedClipId = movingClip.id, dragVelocityX = -0.85f)
        )
        assertTrue("Rightward drag should lag prop angle negatively", dragKickRight.angleDeg < -2f)
        assertTrue("Leftward drag should lag prop angle positively", dragKickLeft.angleDeg > 2f)

        // Moshi JSON round-trip preserves propSwayStrength
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(TimelineClip::class.java)
        val restored = adapter.fromJson(adapter.toJson(movingClip.copy(propSwayStrength = 1.65f)))
        assertEquals(1.65f, restored?.propSwayStrength ?: 0f, 0.001f)
    }

    @Test
    fun `analytical 2-bone IK solver handles 10 stage handles, bendFlip, torso lean, pose mirroring, and camera inverse transforms`() {
        // 1. Camera screen-to-world inverse transform round-trip under zoom + pan + rotation
        val cam = com.example.engine.EvaluatedCamera(x = 0.35f, y = 0.62f, zoom = 1.75f, rotation = 18f)
        val stageW = 1280f
        val stageH = 720f
        val (worldCenterX, worldCenterY) = com.example.engine.IkRigSolver.screenNormToWorldNorm(0.5f, 0.5f, cam, stageW, stageH)
        assertEquals(cam.x, worldCenterX, 0.0005f)
        assertEquals(cam.y, worldCenterY, 0.0005f)

        // Screen delta inverse scales inversely with camera zoom
        val unrotatedCam = com.example.engine.EvaluatedCamera(x = 0.5f, y = 0.5f, zoom = 2.0f, rotation = 0f)
        val (dxW, dyW) = com.example.engine.IkRigSolver.screenDeltaToWorldNormDelta(128f, 72f, unrotatedCam, stageW, stageH)
        assertEquals(0.05f, dxW, 0.0005f)
        assertEquals(0.05f, dyW, 0.0005f)

        // 2. Stage IK handles include all 10 joints (including LEFT_KNEE & RIGHT_KNEE)
        val project = BuiltInCatalog.sceneTemplates.first().buildProject("IK Stage Test")
        val charClip = project.activeScene.clips.first { it.characterId != null }
        val rig = BuiltInCatalog.builtInCharacters.first { it.id == charClip.characterId }
        val eval = KeyframeEvaluator.evaluate(charClip, 0)
        val basePose = ProceduralRigRenderer.PoseAngles()
        val handles = com.example.engine.IkRigSolver.computeStageIkHandles(charClip, rig, eval, basePose, stageW, stageH)
        assertEquals(10, handles.size)
        assertTrue(handles.any { it.id == IkHandleId.LEFT_KNEE })
        assertTrue(handles.any { it.id == IkHandleId.RIGHT_KNEE })

        // 3. Analytical 2-bone IK arm solve converges close to target & bendFlip inverts elbow bend
        val targetX = eval.x + 0.09f
        val targetY = eval.y - 0.06f
        val naturalSolved = com.example.engine.IkRigSolver.solveIkDragToOffsets(
            handleId = IkHandleId.RIGHT_HAND,
            targetNx = targetX,
            targetNy = targetY,
            clip = charClip,
            rig = rig,
            transform = eval,
            basePose = basePose,
            currentOffsets = PoseKeyframeOffsets(),
            stageWidth = stageW,
            stageHeight = stageH,
            bendFlip = false
        )
        val flippedSolved = com.example.engine.IkRigSolver.solveIkDragToOffsets(
            handleId = IkHandleId.RIGHT_HAND,
            targetNx = targetX,
            targetNy = targetY,
            clip = charClip,
            rig = rig,
            transform = eval,
            basePose = basePose,
            currentOffsets = PoseKeyframeOffsets(),
            stageWidth = stageW,
            stageHeight = stageH,
            bendFlip = true
        )
        assertNotEquals("bendFlip should produce a different elbow bend angle", naturalSolved.rightForearmOffset, flippedSolved.rightForearmOffset, 5f)

        // Verify solved FK hand handle actually moves near the requested target!
        val posedHandles = com.example.engine.IkRigSolver.computeStageIkHandles(
            charClip, rig, eval.copy(poseOffsets = naturalSolved),
            basePose.withOffsets(naturalSolved, FacialExpression.AUTO), stageW, stageH
        )
        val solvedHand = posedHandles.first { it.id == IkHandleId.RIGHT_HAND }
        val err = kotlin.math.hypot(solvedHand.nx - targetX, solvedHand.ny - targetY)
        assertTrue("2-bone IK hand should reach within 0.035 normalized stage units of target, got $err", err < 0.035f)

        // 4. Analytical 2-bone leg IK moves thigh + knee together
        val legSolved = com.example.engine.IkRigSolver.solveIkDragToOffsets(
            handleId = IkHandleId.LEFT_FOOT,
            targetNx = eval.x - 0.06f,
            targetNy = eval.y + 0.14f,
            clip = charClip,
            rig = rig,
            transform = eval,
            basePose = basePose,
            currentOffsets = PoseKeyframeOffsets(),
            stageWidth = stageW,
            stageHeight = stageH,
            bendFlip = false
        )
        assertNotEquals(0f, legSolved.leftLegOffset)

        // 5. Mirror pose swaps left and right limb offsets cleanly
        val mirrored = com.example.engine.IkRigSolver.mirrorPoseOffsets(
            PoseKeyframeOffsets(
                torsoTiltOffset = 12f,
                rightArmOffset = -45f,
                rightForearmOffset = -30f,
                leftArmOffset = 15f,
                leftForearmOffset = 10f,
                rightLegOffset = 25f,
                rightKneeOffset = 40f,
                leftLegOffset = -10f,
                leftKneeOffset = 5f
            )
        )
        assertEquals(-12f, mirrored.torsoTiltOffset, 0.001f)
        assertEquals(-15f, mirrored.rightArmOffset, 0.001f)
        assertEquals(45f, mirrored.leftArmOffset, 0.001f)
        assertEquals(-5f, mirrored.rightKneeOffset, 0.001f)
        assertEquals(-40f, mirrored.leftKneeOffset, 0.001f)
    }

    @Test
    fun `project mutator preserves handPoseOverride, blinkOverride, and facialPresetId on upsert, toggle, and split`() {
        val base = BuiltInCatalog.sceneTemplates.first().buildProject("Keyframe Preserve Test")
        val charClip = base.activeScene.clips.first { it.characterId != null }

        val withOverrides = ProjectMutator.upsertKeyframeAtFrame(base, charClip.id, globalFrame = 0) { kf ->
            kf.copy(
                handPoseOverride = HandPoseType.POINT,
                blinkOverride = true,
                facialPresetId = com.example.engine.FacialExpressionEngine.PRESET_HAPPY,
                expressionOverride = FacialExpression.HAPPY
            )
        }
        // Upsert a position-only keyframe at frame 12 -> should inherit all overrides!
        val atFrame12 = ProjectMutator.upsertKeyframeAtFrame(withOverrides, charClip.id, globalFrame = 12) { kf ->
            kf.copy(x = 0.65f)
        }
        val kf12 = atFrame12.activeScene.clips.first { it.id == charClip.id }.keyframes.first { it.frame == 12 }
        assertEquals(HandPoseType.POINT, kf12.handPoseOverride)
        assertEquals(true, kf12.blinkOverride)
        assertEquals(com.example.engine.FacialExpressionEngine.PRESET_HAPPY, kf12.facialPresetId)

        // Split clip at frame 20 -> second half frame 0 should preserve all overrides!
        val split = ProjectMutator.splitClipAtFrame(atFrame12, charClip.id, globalFrame = 20)
        val secondHalf = split.activeScene.clips.first { it.startFrame == 20 }
        val secondKf0 = secondHalf.keyframes.first { it.frame == 0 }
        assertEquals(HandPoseType.POINT, secondKf0.handPoseOverride)
        assertEquals(true, secondKf0.blinkOverride)
        assertEquals(com.example.engine.FacialExpressionEngine.PRESET_HAPPY, secondKf0.facialPresetId)
    }

    @Test
    fun `advanced camera control layer supports pan zoom rotation keyframes enable lock easing and json persistence`() {
        var project = BuiltInCatalog.sceneTemplates.first().buildProject("Camera Layer Test")
        project = ProjectMutator.upsertCameraKeyframeAtFrame(project, globalFrame = 0) {
            it.copy(x = 0.40f, y = 0.50f, zoom = 1.0f, rotation = 0f, easing = EasingType.LINEAR)
        }
        project = ProjectMutator.upsertCameraKeyframeAtFrame(project, globalFrame = 20) {
            it.copy(x = 0.60f, y = 0.40f, zoom = 2.0f, rotation = 20f, easing = EasingType.EASE_OUT)
        }

        // Midpoint evaluation at frame 10 (LINEAR between frame 0 and 20)
        val midCam = KeyframeEvaluator.evaluateCamera(project.activeScene, globalFrame = 10)
        assertEquals(0.50f, midCam.x, 0.005f)
        assertEquals(0.45f, midCam.y, 0.005f)
        assertEquals(1.50f, midCam.zoom, 0.005f)
        assertEquals(10f, midCam.rotation, 0.2f)

        // Update easing on keyframe 0 to HOLD -> midpoint stays at keyframe 0 values until frame 20
        val holdProject = ProjectMutator.updateCameraKeyframeEasing(project, globalFrame = 0, easing = EasingType.HOLD)
        val holdMid = KeyframeEvaluator.evaluateCamera(holdProject.activeScene, globalFrame = 10)
        assertEquals(0.40f, holdMid.x, 0.001f)
        assertEquals(1.0f, holdMid.zoom, 0.001f)
        assertEquals(0f, holdMid.rotation, 0.001f)

        // Disabling camera layer bypasses camera transform without deleting keyframes
        val bypassed = ProjectMutator.toggleCameraLayerEnabled(project)
        assertFalse(bypassed.activeScene.cameraLayerEnabled)
        assertEquals(2, bypassed.activeScene.cameraKeyframes.size)
        val bypassedEval = KeyframeEvaluator.evaluateCamera(bypassed.activeScene, globalFrame = 10)
        assertTrue(bypassedEval.isIdentity)

        // Locking camera layer blocks upsert, delete, preset, and reset mutations
        val locked = ProjectMutator.toggleCameraLayerLocked(project)
        assertTrue(locked.activeScene.cameraLayerLocked)
        val attemptedEdit = ProjectMutator.upsertCameraKeyframeAtFrame(locked, globalFrame = 10) { it.copy(zoom = 3.0f) }
        assertEquals(2, attemptedEdit.activeScene.cameraKeyframes.size)
        val attemptedReset = ProjectMutator.resetCameraKeyframes(locked)
        assertEquals(2, attemptedReset.activeScene.cameraKeyframes.size)

        // Full ProjectDocument Moshi JSON round-trip (ensures saved project & exports keep exact camera values)
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ProjectDocument::class.java)
        val restored = adapter.fromJson(adapter.toJson(locked))
        assertNotNull(restored)
        val restoredScene = restored!!.activeScene
        assertTrue(restoredScene.cameraLayerEnabled)
        assertTrue(restoredScene.cameraLayerLocked)
        assertEquals(2, restoredScene.cameraKeyframes.size)
        assertEquals(0.60f, restoredScene.cameraKeyframes[1].x, 0.001f)
        assertEquals(2.0f, restoredScene.cameraKeyframes[1].zoom, 0.001f)
        assertEquals(20f, restoredScene.cameraKeyframes[1].rotation, 0.001f)
    }

    @Test
    fun `advanced camera control presets generate valid multi-keyframe sequences across all 12 presets and intensity scaling`() {
        val presets = com.example.engine.CameraPresetEngine.presets
        assertTrue("Expected at least 12 advanced camera presets", presets.size >= 12)

        val base = BuiltInCatalog.sceneTemplates.first().buildProject("Camera Presets Test")
        for (preset in presets) {
            val applied = ProjectMutator.applyCinematicCameraPreset(
                project = base,
                startFrame = 8,
                presetId = preset.id,
                intensity = 1.0f
            )
            val kfs = applied.activeScene.cameraKeyframes
            assertTrue("Preset ${preset.id} should produce >= 2 keyframes", kfs.size >= 2)
            assertTrue("Preset ${preset.id} keyframes must be sorted ascending", kfs.zipWithNext().all { (a, b) -> a.frame < b.frame })
            kfs.forEach { kf ->
                assertTrue(kf.x in 0.05f..0.95f)
                assertTrue(kf.y in 0.05f..0.95f)
                assertTrue(kf.zoom in 0.35f..3.5f)
                assertTrue(kf.rotation in -180f..180f)
            }
        }

        // Intensity scaling increases motion amplitude monotonically for push_in
        val subtle = ProjectMutator.applyCinematicCameraPreset(base, startFrame = 0, presetId = "push_in", intensity = 0.5f)
        val dramatic = ProjectMutator.applyCinematicCameraPreset(base, startFrame = 0, presetId = "push_in", intensity = 1.6f)
        val subtleEndZoom = subtle.activeScene.cameraKeyframes.last().zoom
        val dramaticEndZoom = dramatic.activeScene.cameraKeyframes.last().zoom
        assertTrue("Expected dramatic push_in zoom ($dramaticEndZoom) > subtle ($subtleEndZoom)", dramaticEndZoom > subtleEndZoom + 0.25f)
    }

    @Test
    fun `facial expression overrides toggle mouth eye and eyebrow shapes independently over animations and persist in project json`() {
        val base = BuiltInCatalog.sceneTemplates.first().buildProject("Facial Overrides Test")
        val charClip = base.activeScene.clips.first { it.characterId != null }

        // Stamp independent Mouth + Eye + Eyebrow overrides at frame 6
        val overridden = ProjectMutator.setFacialShapeOverrides(
            project = base,
            clipId = charClip.id,
            globalFrame = 6,
            mouthOverride = MouthShapeOverride.SHOCK_O,
            eyeOverride = EyeShapeOverride.WINK_RIGHT,
            eyebrowOverride = EyebrowShapeOverride.FURROWED_ANGRY
        )

        // Add a transform-only keyframe at frame 18 -> overrides should still hold via independent track scan!
        val movedLater = ProjectMutator.upsertKeyframeAtFrame(overridden, charClip.id, globalFrame = 18) {
            it.copy(x = 0.72f)
        }
        val movedClip = movedLater.activeScene.clips.first { it.id == charClip.id }
        val evalAt24 = KeyframeEvaluator.evaluate(movedClip, globalFrame = 24)
        assertEquals(MouthShapeOverride.SHOCK_O, evalAt24.mouthShapeOverride)
        assertEquals(EyeShapeOverride.WINK_RIGHT, evalAt24.eyeShapeOverride)
        assertEquals(EyebrowShapeOverride.FURROWED_ANGRY, evalAt24.eyebrowShapeOverride)

        // Evaluate FacialExpressionEngine with independent overrides on top of a HAPPY preset
        val organic = com.example.engine.FacialExpressionEngine.evaluateOrganicFace(
            activePresetId = com.example.engine.FacialExpressionEngine.PRESET_HAPPY,
            previousPresetId = null,
            fallbackExpression = FacialExpression.HAPPY,
            framesSinceKey = 20,
            globalFrame = 24,
            fps = 24,
            mouthOverride = evalAt24.mouthShapeOverride!!,
            eyeOverride = evalAt24.eyeShapeOverride!!,
            eyebrowOverride = evalAt24.eyebrowShapeOverride!!
        )
        // Mouth is SHOCK_O (wide open, LIP_SHOCKED)
        assertTrue("SHOCK_O mouth should be widely open, got ${organic.mouthOpen}", organic.mouthOpen >= 0.85f)
        assertEquals(AtlasPartSlot.LIP_SHOCKED, organic.resolvedMouthSlot)
        // Eyes are WINK_RIGHT (left open, right closed/winking)
        assertFalse(organic.isBlinkingLeft)
        assertTrue(organic.isBlinkingRight)
        // Eyebrows are FURROWED_ANGRY (negative lift, positive inward tilt, BROW_ANGRY)
        assertTrue("Furrowed angry brow lift should be negative, got ${organic.leftBrowLift}", organic.leftBrowLift < -0.4f)
        assertTrue("Furrowed angry brow tilt should be > 15deg, got ${organic.leftBrowTiltDeg}", organic.leftBrowTiltDeg > 15f)
        assertEquals(AtlasPartSlot.BROW_ANGRY, organic.resolvedBrowSlot)

        // Clearing overrides restores AUTO cleanly
        val cleared = ProjectMutator.clearFacialShapeOverrides(movedLater, charClip.id, globalFrame = 24)
        val clearedClip = cleared.activeScene.clips.first { it.id == charClip.id }
        val evalCleared = KeyframeEvaluator.evaluate(clearedClip, globalFrame = 24)
        assertEquals(MouthShapeOverride.AUTO, evalCleared.mouthShapeOverride)
        assertEquals(EyeShapeOverride.AUTO, evalCleared.eyeShapeOverride)
        assertEquals(EyebrowShapeOverride.AUTO, evalCleared.eyebrowShapeOverride)

        // Moshi JSON round-trip persistence check
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ProjectDocument::class.java)
        val restored = adapter.fromJson(adapter.toJson(overridden))
        assertNotNull(restored)
        val restoredClip = restored!!.activeScene.clips.first { it.id == charClip.id }
        assertEquals(MouthShapeOverride.SHOCK_O, restoredClip.mouthShapeOverride)
        assertEquals(EyeShapeOverride.WINK_RIGHT, restoredClip.eyeShapeOverride)
        assertEquals(EyebrowShapeOverride.FURROWED_ANGRY, restoredClip.eyebrowShapeOverride)
    }

    @Test
    fun `studio capability registry dynamically introspects all live enums without hardcoding and exports blueprint v3`() {
        val snapshot = com.example.engine.StudioCapabilityRegistry.introspect()
        assertEquals(AnimationPresetType.entries.size, snapshot.animationPresets.size)
        assertEquals(FacialExpression.entries.size, snapshot.facialExpressions.size)
        assertEquals(MouthShapeOverride.entries.size, snapshot.mouthShapeOverrides.size)
        assertEquals(EyeShapeOverride.entries.size, snapshot.eyeShapeOverrides.size)
        assertEquals(EyebrowShapeOverride.entries.size, snapshot.eyebrowShapeOverrides.size)
        assertEquals(HandPoseType.entries.size, snapshot.handPoses.size)
        assertEquals(CharacterOrientation.entries.size, snapshot.orientations.size)
        assertEquals(IkHandleId.entries.size, snapshot.ikJointIds.size)
        assertEquals(CameraShotType.entries.size, snapshot.cameraShotTypes.size)
        assertEquals(SceneParticleType.entries.size, snapshot.vfxStyles.size)
        assertEquals(SceneLightingMood.entries.size, snapshot.colourGradingPresets.size)
        assertEquals(ClipMaskType.entries.size, snapshot.maskTypes.size)
        assertEquals(EasingType.entries.size, snapshot.curveAndEasingTypes.size)
        assertEquals(TransitionType.entries.size, snapshot.transitions.size)
        assertEquals(LayerBlendMode.entries.size, snapshot.layerBlendModes.size)
        assertEquals(AspectRatioPreset.entries.size, snapshot.aspectRatios.size)
        assertTrue(snapshot.aspectRatios.any { it.id == "4:5" })
        assertTrue(snapshot.clipOperations.map { it.id }.containsAll(listOf("split", "flipX", "flipY", "duplicate", "delete", "speed", "trim")))

        val project = BuiltInCatalog.sceneTemplates.first().buildProject("Blueprint Film")
        val audioAsset = LocalMediaAsset(
            id = "aud_voice_1",
            name = "Hero Dialogue",
            category = "Audio",
            type = MediaAssetType.AUDIO,
            localFilePath = "/tmp/dialogue.wav"
        )
        val blueprint = com.example.engine.AIBlueprintExporter.buildBlueprint(
            project = project,
            characters = BuiltInCatalog.builtInCharacters.take(2),
            mediaAssets = listOf(audioAsset),
            audioDurationResolver = { 4250L }
        )
        assertEquals(3, blueprint.schemaVersion)
        assertEquals(0.82f, blueprint.projectMeta.coordinateSystem.groundY, 0.001f)
        assertEquals(2, blueprint.projectAssets.characters.size)
        assertEquals(1, blueprint.projectAssets.audioClips.size)
        assertEquals(4.25, blueprint.projectAssets.audioClips.first().durationSec, 0.001)

        val json = com.example.engine.AIBlueprintExporter.exportToJson(
            project = project,
            characters = BuiltInCatalog.builtInCharacters.take(2),
            mediaAssets = listOf(audioAsset),
            prettyPrint = false,
            audioDurationResolver = { 4250L }
        )
        assertTrue(json.contains("\"framecraft.blueprint\""))
        assertTrue(json.contains("\"universalExtensionSchemas\""))
        assertTrue(json.contains("\"inlineSvgAssetExample\""))
        assertTrue(json.contains("\"customAnimationExample\""))
        assertTrue(json.contains("\"customTransitionExample\""))
    }

    @Test
    fun `inline svg asset spawner sanitizes untrusted xml and parses hierarchical sub-group pivots`() {
        // Reject dangerous constructs
        assertFalse(com.example.engine.InlineSvgAssetSpawner.sanitizeSvgXml("<svg><script>alert(1)</script></svg>").isValid)
        assertFalse(com.example.engine.InlineSvgAssetSpawner.sanitizeSvgXml("<svg><foreignObject><div/></foreignObject></svg>").isValid)
        assertFalse(com.example.engine.InlineSvgAssetSpawner.sanitizeSvgXml("<svg><filter id='f'><feGaussianBlur/></filter></svg>").isValid)
        assertFalse(com.example.engine.InlineSvgAssetSpawner.sanitizeSvgXml("<!DOCTYPE svg><svg></svg>").isValid)
        assertFalse(com.example.engine.InlineSvgAssetSpawner.sanitizeSvgXml("<svg><a href='javascript:void(0)'></a></svg>").isValid)

        // Parse clean hierarchical SVG with data-pivot attributes
        val treeXml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256">
                <g id="trunk" data-pivot="128,232">
                    <rect x="112" y="136" width="32" height="104" fill="#7C4A27"/>
                </g>
                <g id="tree_canopy" data-pivot="128,140">
                    <circle cx="128" cy="90" r="60" fill="#2E8B57"/>
                    <g id="nested_leaf"><circle cx="95" cy="95" r="14" fill="#3CB371"/></g>
                </g>
            </svg>
        """.trimIndent()
        val def = InlineSvgAssetDef(id = "svg_tree_1", type = InlineSvgAssetType.TREE, viewBox = "0 0 256 256", svgXml = treeXml)
        val parsed = com.example.engine.InlineSvgAssetSpawner.parseInlineSvgDocument(def)
        assertNotNull(parsed)
        assertEquals(256f, parsed!!.viewBox.width, 0.01f)
        assertEquals(2, parsed.subParts.size)
        assertEquals("trunk", parsed.subParts[0].nodeId)
        assertEquals(232f, parsed.subParts[0].pivotY, 0.01f)
        assertEquals("tree_canopy", parsed.subParts[1].nodeId)
        assertEquals(140f, parsed.subParts[1].pivotY, 0.01f)

        // Verify CHARACTER inline SVG builds a native CharacterRigDefinition (Atlas Pro for 2048x2048)
        val charDef = InlineSvgAssetDef(
            id = "svg_ninja_hero",
            type = InlineSvgAssetType.CHARACTER,
            viewBox = "0 0 2048 2048",
            svgXml = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 2048 2048'><rect width='2048' height='2048' fill='none'/></svg>"
        )
        val rig = com.example.engine.InlineSvgAssetSpawner.buildCharacterRigFromInlineSvg(charDef, "Ninja Hero")
        assertEquals("Ninja Hero", rig.name)
        assertEquals(CustomRigMode.ATLAS_RIG_PRO, rig.skeletonRig?.rigMode)
        assertNotNull(rig.spriteAtlasRig)
    }

    @Test
    fun `universal custom animation engine evaluates ONCE, LOOP, PING_PONG, cubicBezier, and blends character bones`() {
        val anim = CustomAnimationDef(
            id = "wing_flap",
            durationSec = 1.0f,
            loopMode = CustomAnimLoopMode.PING_PONG,
            tracks = listOf(
                CustomAnimTrack(
                    targetNodeId = "RIGHT_ARM",
                    keyframes = listOf(
                        CustomAnimKeyframe(t = 0f, rotation = -20f, cubicBezier = listOf(0.42f, 0f, 0.58f, 1f)),
                        CustomAnimKeyframe(t = 1f, rotation = 40f, cubicBezier = listOf(0.42f, 0f, 0.58f, 1f))
                    )
                ),
                CustomAnimTrack(
                    targetNodeId = "HEAD",
                    keyframes = listOf(
                        CustomAnimKeyframe(t = 0f, rotation = 0f),
                        CustomAnimKeyframe(t = 1f, rotation = 12f)
                    )
                )
            )
        )

        // Frame 0 -> start (-20 deg); Frame 24 (1.0s at 24fps) -> peak (+40 deg); Frame 48 (2.0s) -> ping-pong back (-20 deg)
        val at0 = com.example.engine.UniversalCustomAnimationEngine.evaluateAllTracks(anim, localFrame = 0, fps = 24)
        val at24 = com.example.engine.UniversalCustomAnimationEngine.evaluateAllTracks(anim, localFrame = 24, fps = 24)
        val at48 = com.example.engine.UniversalCustomAnimationEngine.evaluateAllTracks(anim, localFrame = 48, fps = 24)
        assertEquals(-20f, at0["RIGHT_ARM"]!!.rotation, 0.2f)
        assertEquals(40f, at24["RIGHT_ARM"]!!.rotation, 0.2f)
        assertEquals(-20f, at48["RIGHT_ARM"]!!.rotation, 0.2f)

        // ONCE clamps at 1.0f
        assertEquals(
            1f,
            com.example.engine.UniversalCustomAnimationEngine.computeNormalizedTime(60, 24, 1.0f, CustomAnimLoopMode.ONCE),
            0.001f
        )
        // LOOP wraps cleanly
        assertEquals(
            0.5f,
            com.example.engine.UniversalCustomAnimationEngine.computeNormalizedTime(36, 24, 1.0f, CustomAnimLoopMode.LOOP),
            0.001f
        )

        // Blend bone offsets additively with existing manual IK offsets
        val manualIk = PoseKeyframeOffsets(rightArmOffset = 10f, headNodOffset = -3f)
        val blended = com.example.engine.UniversalCustomAnimationEngine.blendIntoPoseOffsets(manualIk, at24)
        assertEquals(50f, blended.rightArmOffset, 0.2f)
        assertEquals(9f, blended.headNodOffset, 0.2f)
    }

    @Test
    fun `universal custom transition engine evaluates eased transforms, flash bell curve, and svg path tokens`() {
        val trans = CustomTransitionDef(
            id = "whip_iris",
            durationSec = 0.6f,
            clipPathType = CustomTransitionClipType.IRIS_CIRCLE,
            angleDeg = 15f,
            outgoing = CustomTransitionTransform(dx = -0.2f, dy = 0f, scale = 1.2f, rotation = -4f, alpha = 0f),
            incoming = CustomTransitionTransform(dx = 0.2f, dy = 0f, scale = 0.9f, rotation = 4f, alpha = 1f),
            flashOverlay = CustomTransitionFlash(colorHex = "#FDE68A", peakAlpha = 0.6f),
            cubicBezier = listOf(0.22f, 1.0f, 0.36f, 1.0f)
        )
        val start = com.example.engine.UniversalCustomTransitionEngine.evaluate(trans, 0f)
        val mid = com.example.engine.UniversalCustomTransitionEngine.evaluate(trans, 0.5f)
        val end = com.example.engine.UniversalCustomTransitionEngine.evaluate(trans, 1f)

        assertEquals(0f, start.flashAlpha, 0.001f)
        assertEquals(0.6f, mid.flashAlpha, 0.01f)
        assertEquals(0f, end.flashAlpha, 0.001f)
        assertEquals(-0.2f, end.outgoingDx, 0.01f)
        assertEquals(0f, end.incomingDx, 0.01f)
        assertEquals(1f, end.incomingScale, 0.01f)

        val tokens = com.example.engine.UniversalCustomTransitionEngine.tokenizeSvgPath("M 0.5 0 L 1 0.5 L 0.5 1 L 0 0.5 Z")
        assertTrue(tokens.contains("M") && tokens.contains("Z") && tokens.size == 13)
    }

    @Test
    fun `director script receiver validates dry-run, compiles full studio tools, auto-focus camera, lip-sync, and round-trips json`() {
        val baseProject = BuiltInCatalog.sceneTemplates.first().buildProject("Director Base")
        val chars = BuiltInCatalog.builtInCharacters.take(2)
        val heroId = chars[0].id
        val buddyId = chars[1].id

        val scriptJson = """
        {
          "format": "framecraft.director_script",
          "schemaVersion": 3,
          "projectName": "Moonlit Forest Film",
          "ratio": "4:5",
          "fps": 24,
          "inlineSvgAssets": [
            {
              "id": "svg_pine",
              "name": "Pine Tree",
              "type": "TREE",
              "viewBox": "0 0 256 256",
              "svgXml": "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 256 256'><g id='tree_canopy' data-pivot='128,140'><circle cx='128' cy='92' r='60' fill='#2E8B57'/></g></svg>"
            },
            {
              "id": "svg_bad",
              "type": "PROP",
              "svgXml": "<svg><script>bad()</script></svg>"
            }
          ],
          "customAnimations": {
            "canopy_sway": {
              "durationSec": 2.0,
              "loopMode": "PING_PONG",
              "tracks": [
                {
                  "targetNodeId": "tree_canopy",
                  "keyframes": [
                    { "t": 0.0, "rotation": -6.0 },
                    { "t": 1.0, "rotation": 6.0 }
                  ]
                }
              ]
            }
          },
          "customTransitions": {
            "moon_iris": {
              "durationSec": 0.5,
              "clipPathType": "IRIS_CIRCLE",
              "flashOverlay": { "colorHex": "#FFFFFF", "peakAlpha": 0.3 }
            }
          },
          "scenes": [
            {
              "name": "Act 1",
              "durationSec": 4.0,
              "colourGrading": "MIDNIGHT_BLUE",
              "vfx": { "particleType": "FIREFLIES", "intensity": 0.8, "vignette": 0.35 },
              "customTransitionInId": "moon_iris",
              "layers": [
                { "id": "l_tree", "name": "Forest", "type": "IMAGE", "zIndex": 0, "blendMode": "SCREEN" },
                { "id": "l_hero", "name": "Hero", "type": "CHARACTER", "zIndex": 1, "blendMode": "NORMAL" },
                { "id": "l_buddy", "name": "Buddy", "type": "CHARACTER", "zIndex": 2, "parentLayerId": "l_hero" }
              ],
              "clips": [
                {
                  "id": "c_tree",
                  "layerId": "l_tree",
                  "inlineSvgAssetId": "svg_pine",
                  "customAnimationId": "canopy_sway",
                  "mask": { "type": "CIRCLE", "radius": 0.48 },
                  "startSec": 0.0,
                  "durationSec": 4.0,
                  "keyframes": [{ "tSec": 0.0, "x": 0.2, "y": 0.6 }]
                },
                {
                  "id": "c_hero",
                  "layerId": "l_hero",
                  "characterId": "$heroId",
                  "lipSyncAudioId": "aud_mock",
                  "startSec": 0.0,
                  "durationSec": 4.0,
                  "motionPath": [
                    { "x": 0.25, "y": 0.82 },
                    { "x": 0.40, "y": 0.72 },
                    { "x": 0.55, "y": 0.82 }
                  ],
                  "keyframes": [
                    { "tSec": 0.0, "x": 0.25, "y": 0.82, "ikPose": { "headNod": 12.0, "rightArm": -25.0 } },
                    { "tSec": 3.0, "x": 0.55, "y": 0.82 }
                  ]
                },
                {
                  "id": "c_buddy",
                  "layerId": "l_buddy",
                  "characterId": "$buddyId",
                  "startSec": 0.0,
                  "durationSec": 4.0,
                  "keyframes": [{ "tSec": 0.0, "x": 0.75, "y": 0.82 }]
                }
              ],
              "clipOperations": [
                { "op": "flipX", "targetClipId": "c_buddy", "enabled": true },
                { "op": "speed", "targetClipId": "c_buddy", "multiplier": 1.5 },
                { "op": "split", "targetClipId": "c_buddy", "atSec": 2.0, "newClipId": "c_buddy_p2" },
                { "op": "duplicate", "targetClipId": "c_tree", "offsetSec": 0.5, "newClipId": "c_tree_dup" },
                { "op": "delete", "targetClipId": "c_tree_dup" }
              ],
              "cameraShots": [
                { "tSec": 0.0, "shotType": "WIDE_ESTABLISHING" },
                { "tSec": 1.0, "shotType": "CLOSE_UP_SPEAKER", "focusTargetCharacterId": "$heroId" },
                { "tSec": 2.5, "shotType": "CAMERA_SHAKE", "focusTargetCharacterId": "$buddyId", "shakeIntensity": 0.5 }
              ]
            }
          ]
        }
        """.trimIndent()

        // 1. Dry-run catches rejected malicious SVG as warning while keeping script valid
        val (_, dryRun) = com.example.engine.DirectorScriptReceiver.validateAndDryRun(scriptJson, chars, emptyList())
        assertTrue(dryRun.isValid)
        assertEquals(1, dryRun.inlineSvgCount)
        assertEquals(1, dryRun.customAnimationCount)
        assertEquals(1, dryRun.customTransitionCount)
        assertEquals(3, dryRun.cameraShotCount)
        assertEquals(5, dryRun.clipOperationCount)
        assertTrue(dryRun.warnings.any { it.contains("svg_bad") })

        // 2. Compile in REPLACE_TIMELINE mode
        val compiled = com.example.engine.DirectorScriptReceiver.compileIntoProject(
            context = null,
            scriptJson = scriptJson,
            currentProject = baseProject,
            existingCharacters = chars,
            existingMediaAssets = emptyList(),
            importMode = com.example.engine.DirectorScriptImportMode.REPLACE_TIMELINE
        ).getOrThrow()

        val proj = compiled.project
        assertEquals("Moonlit Forest Film", proj.name)
        assertEquals(AspectRatioPreset.RATIO_4_5, proj.aspectRatioPreset)
        assertEquals(1080, proj.width)
        assertEquals(1350, proj.height)
        assertTrue(proj.inlineSvgAssets.containsKey("svg_pine"))
        assertFalse(proj.inlineSvgAssets.containsKey("svg_bad"))
        assertTrue(proj.customAnimations.containsKey("canopy_sway"))
        assertTrue(proj.customTransitions.containsKey("moon_iris"))

        val scene = proj.activeScene
        assertEquals(SceneLightingMood.MIDNIGHT_BLUE, scene.vfxConfig.lightingMood)
        assertEquals(SceneParticleType.FIREFLIES, scene.vfxConfig.particleType)
        assertTrue("CAMERA_SHAKE shot should activate cameraShakeIntensity", scene.vfxConfig.cameraShakeIntensity >= 0.45f)
        assertEquals("moon_iris", scene.customTransitionInId)

        // Verify blendMode & parentLayerId
        assertEquals(LayerBlendMode.SCREEN, scene.layers.first { it.id == "l_tree" }.blendMode)
        assertEquals("l_hero", scene.layers.first { it.id == "l_buddy" }.parentLayerId)

        // Verify clip operations: split created c_buddy_p2, flipX & speed applied, duplicate+delete removed c_tree_dup
        val buddy1 = scene.clips.first { it.id == "c_buddy" }
        val buddy2 = scene.clips.firstOrNull { it.id == "c_buddy_p2" }
        assertNotNull(buddy2)
        assertTrue(buddy1.flipHorizontal)
        assertEquals(1.5f, buddy1.speedMultiplier, 0.01f)
        assertNull(scene.clips.firstOrNull { it.id == "c_tree_dup" })

        // Verify masking, curved Bezier motionPath, IK pose, and auto lip-sync mouth keyframes on hero
        val treeClip = scene.clips.first { it.id == "c_tree" }
        assertEquals(ClipMaskType.CIRCLE, treeClip.maskType)
        val heroClip = scene.clips.first { it.id == "c_hero" }
        assertEquals(3, heroClip.motionPathControlPoints.size)
        val midHero = KeyframeEvaluator.evaluate(heroClip, globalFrame = 36)
        assertTrue("Curved Bezier motionPath should arc above y=0.82 at midpoint, got ${midHero.y}", midHero.y < 0.80f)
        assertTrue("Auto lip-sync should generate multiple MouthShapeOverride keyframes", heroClip.keyframes.count { it.mouthShapeOverride != null } >= 3)
        assertEquals(12f, heroClip.keyframes.first().poseOffsets.headNodOffset, 0.1f)

        // Verify cinematic auto-focus camera zoomed into speaker on CLOSE_UP_SPEAKER shot
        val camAtCloseUp = KeyframeEvaluator.evaluateCamera(scene, globalFrame = 24)
        assertTrue("CLOSE_UP_SPEAKER should zoom >= 1.7x, got ${camAtCloseUp.zoom}", camAtCloseUp.zoom >= 1.7f)

        // 3. APPEND_SCENES mode preserves existing scenes and appends new ones
        val appended = com.example.engine.DirectorScriptReceiver.compileIntoProject(
            context = null,
            scriptJson = scriptJson,
            currentProject = baseProject,
            existingCharacters = chars,
            existingMediaAssets = emptyList(),
            importMode = com.example.engine.DirectorScriptImportMode.APPEND_SCENES
        ).getOrThrow()
        assertEquals(baseProject.scenes.size + 1, appended.project.scenes.size)

        // 4. Full ProjectDocument Moshi round-trip preserves inlineSvgAssets, customAnimations, customTransitions
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ProjectDocument::class.java)
        val restored = adapter.fromJson(adapter.toJson(proj))
        assertNotNull(restored)
        assertEquals(1, restored!!.inlineSvgAssets.size)
        assertEquals(1, restored.customAnimations.size)
        assertEquals(1, restored.customTransitions.size)
        assertEquals("moon_iris", restored.activeScene.customTransitionInId)
    }

    @Test
    fun `IK Pose Library persists locally in JSON across restarts, registers in StudioCapabilityRegistry, exports in AI Blueprint, and resolves in DirectorScriptReceiver`() {
        val tempDir = java.nio.file.Files.createTempDirectory("framecraft_pose_lib_test").toFile()
        try {
            val storageFile = java.io.File(tempDir, com.example.data.PoseLibraryStorage.POSE_LIBRARY_FILENAME)
            val thumbsDir = java.io.File(tempDir, com.example.data.PoseLibraryStorage.POSE_THUMBNAILS_DIR)
            val storage1 = com.example.data.PoseLibraryStorage(storageFile, thumbsDir)

            val heroPose = SavedPose(
                id = "pose_hero_landing",
                name = "Hero Landing",
                category = PoseCategory.ACTION,
                ikJointOffsets = PoseKeyframeOffsets(
                    headNodOffset = -12f,
                    torsoTiltOffset = 18f,
                    leftArmOffset = 45f,
                    rightArmOffset = -75f,
                    leftLegOffset = -40f,
                    leftKneeOffset = 75f,
                    rightLegOffset = 22f,
                    rightKneeOffset = 35f
                ),
                gazeTarget = GazeTarget(0.35f, -0.25f),
                headTilt = 8f,
                expression = FacialExpression.ANGRY,
                handPose = HandPoseType.FIST,
                createdAt = 1700000000000L
            )

            // 1. Write & simulate full app restart with a brand-new PoseLibraryStorage instance
            storage1.writePosesBlocking(listOf(heroPose))
            assertTrue("pose_library.json must be created on disk", storageFile.exists())

            val storageAfterRestart = com.example.data.PoseLibraryStorage(storageFile, thumbsDir)
            val reloaded = storageAfterRestart.loadPosesBlocking()
            assertEquals(1, reloaded.size)
            val restoredPose = reloaded.first()
            assertEquals("pose_hero_landing", restoredPose.id)
            assertEquals("Hero Landing", restoredPose.name)
            assertEquals(PoseCategory.ACTION, restoredPose.category)
            assertEquals(-75f, restoredPose.ikJointOffsets.rightArmOffset, 0.01f)
            assertEquals(0.35f, restoredPose.gazeTarget.x, 0.01f)
            assertEquals(8f, restoredPose.headTilt, 0.01f)
            assertEquals(FacialExpression.ANGRY, restoredPose.expression)
            assertEquals(HandPoseType.FIST, restoredPose.handPose)

            // 2. Verify StudioCapabilityRegistry & AIBlueprintExporter automatically include saved poses
            val caps = com.example.engine.StudioCapabilityRegistry.introspect()
            assertTrue(caps.savedPoses.any { it.id == "pose_hero_landing" })
            assertTrue(caps.modularActions.size >= 24)

            val baseProject = BuiltInCatalog.sceneTemplates.first().buildProject("Pose Blueprint Test")
            val blueprintJson = com.example.engine.AIBlueprintExporter.exportToJson(
                project = baseProject,
                characters = BuiltInCatalog.builtInCharacters,
                mediaAssets = emptyList()
            )
            assertTrue("Blueprint JSON must include saved pose ID", blueprintJson.contains("pose_hero_landing"))
            assertTrue("Blueprint JSON must include saved pose name", blueprintJson.contains("Hero Landing"))

            // 3. Verify DirectorScriptReceiver resolves `savedPoseId` onto character keyframes
            val charId = BuiltInCatalog.builtInCharacters.first().id
            val scriptWithSavedPose = """
                {
                  "format": "framecraft.director_script",
                  "schemaVersion": 3,
                  "projectName": "Saved Pose Script",
                  "fps": 24,
                  "scenes": [
                    {
                      "name": "Act 1",
                      "durationSec": 2.0,
                      "layers": [
                        { "id": "l_hero", "name": "Hero", "type": "CHARACTER" }
                      ],
                      "clips": [
                        {
                          "id": "c_hero",
                          "layerId": "l_hero",
                          "characterId": "$charId",
                          "startSec": 0.0,
                          "durationSec": 2.0,
                          "keyframes": [
                            { "tSec": 0.5, "x": 0.5, "y": 0.82, "savedPoseId": "pose_hero_landing" }
                          ]
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()
            val compiled = com.example.engine.DirectorScriptReceiver.compileIntoProject(
                context = null,
                scriptJson = scriptWithSavedPose,
                currentProject = baseProject,
                existingCharacters = BuiltInCatalog.builtInCharacters,
                existingMediaAssets = emptyList(),
                importMode = com.example.engine.DirectorScriptImportMode.REPLACE_TIMELINE
            ).getOrThrow()
            val kf12 = compiled.project.activeScene.clips.first { it.id == "c_hero" }.keyframes.first { it.frame == 12 }
            assertEquals(-75f, kf12.poseOffsets.rightArmOffset, 0.1f)
            assertEquals(-4f, kf12.poseOffsets.headNodOffset, 0.1f) // -12f + headTilt 8f
            assertEquals(FacialExpression.ANGRY, kf12.expressionOverride)
            assertEquals(HandPoseType.FIST, kf12.handPoseOverride)

            // Clean up static registry after test
            com.example.engine.StudioCapabilityRegistry.registerSavedPoses(emptyList())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `Modular Action Preview Mode is strictly non-destructive and track-isolated for Facial, Hands, and Legs`() {
        val baseOffsets = PoseKeyframeOffsets(
            headNodOffset = 5f,
            torsoTiltOffset = 10f,
            leftArmOffset = 25f,
            leftForearmOffset = 15f,
            rightArmOffset = -30f,
            rightForearmOffset = -20f,
            leftLegOffset = 18f,
            leftKneeOffset = 22f,
            rightLegOffset = -16f,
            rightKneeOffset = 14f
        )

        // 1. FACIAL preview ONLY changes face/head; arms & legs stay 100% identical to baseOffsets
        val facialAction = com.example.engine.ModularActionEngine.findById("act_face_gasp")!!
        val facialPreview = com.example.engine.ModularActionEngine.blendPreviewOntoBase(
            basePose = baseOffsets,
            baseExpression = FacialExpression.NEUTRAL,
            baseMouthOverride = MouthShapeOverride.AUTO,
            baseEyeOverride = EyeShapeOverride.AUTO,
            baseEyebrowOverride = EyebrowShapeOverride.AUTO,
            baseHandPose = HandPoseType.POINT,
            baseGaze = GazeTarget(0.2f, -0.1f),
            baseHeadTilt = 3f,
            action = facialAction,
            progress = 0.5f
        )
        assertEquals(FacialExpression.SHOCKED, facialPreview.expression)
        assertEquals(MouthShapeOverride.SHOCK_O, facialPreview.mouthOverride)
        assertEquals(HandPoseType.POINT, facialPreview.handPose)
        assertEquals(baseOffsets.rightArmOffset, facialPreview.poseOffsets.rightArmOffset, 0.001f)
        assertEquals(baseOffsets.leftLegOffset, facialPreview.poseOffsets.leftLegOffset, 0.001f)
        assertEquals(baseOffsets.torsoTiltOffset, facialPreview.poseOffsets.torsoTiltOffset, 0.001f)

        // 2. HANDS preview ONLY changes arms/handPose; face & legs stay 100% identical
        val handsAction = com.example.engine.ModularActionEngine.findById("act_hand_wave")!!
        val handsPreview = com.example.engine.ModularActionEngine.blendPreviewOntoBase(
            basePose = baseOffsets,
            baseExpression = FacialExpression.SAD,
            baseMouthOverride = MouthShapeOverride.FROWN,
            baseEyeOverride = EyeShapeOverride.SAD_DROOP,
            baseEyebrowOverride = EyebrowShapeOverride.WORRIED_SAD,
            baseHandPose = HandPoseType.FIST,
            baseGaze = GazeTarget(0f, 0f),
            baseHeadTilt = 0f,
            action = handsAction,
            progress = 0.5f
        )
        assertEquals(HandPoseType.OPEN, handsPreview.handPose)
        assertNotEquals(baseOffsets.rightArmOffset, handsPreview.poseOffsets.rightArmOffset)
        assertEquals(FacialExpression.SAD, handsPreview.expression)
        assertEquals(MouthShapeOverride.FROWN, handsPreview.mouthOverride)
        assertEquals(baseOffsets.leftLegOffset, handsPreview.poseOffsets.leftLegOffset, 0.001f)
        assertEquals(baseOffsets.rightKneeOffset, handsPreview.poseOffsets.rightKneeOffset, 0.001f)

        // 3. LEGS preview ONLY changes legs/torso; face & arms stay 100% identical
        val legsAction = com.example.engine.ModularActionEngine.findById("act_leg_kick")!!
        val legsPreview = com.example.engine.ModularActionEngine.blendPreviewOntoBase(
            basePose = baseOffsets,
            baseExpression = FacialExpression.HAPPY,
            baseMouthOverride = MouthShapeOverride.SMILE,
            baseEyeOverride = EyeShapeOverride.HAPPY_ARC,
            baseEyebrowOverride = EyebrowShapeOverride.ARCHED_HAPPY,
            baseHandPose = HandPoseType.PROP_GRIP,
            baseGaze = GazeTarget(0f, 0f),
            baseHeadTilt = 0f,
            action = legsAction,
            progress = 0.5f
        )
        assertNotEquals(baseOffsets.rightLegOffset, legsPreview.poseOffsets.rightLegOffset)
        assertEquals(FacialExpression.HAPPY, legsPreview.expression)
        assertEquals(HandPoseType.PROP_GRIP, legsPreview.handPose)
        assertEquals(baseOffsets.leftArmOffset, legsPreview.poseOffsets.leftArmOffset, 0.001f)
        assertEquals(baseOffsets.rightForearmOffset, legsPreview.poseOffsets.rightForearmOffset, 0.001f)

        // 4. Insert at Playhead stamps track-isolated keyframes onto clip without disturbing other tracks
        val baseProject = BuiltInCatalog.sceneTemplates.first().buildProject("Modular Insert Test")
        val charClip = baseProject.activeScene.clips.first { it.characterId != null }
        val seededProject = ProjectMutator.upsertKeyframeAtFrame(baseProject, charClip.id, 10) { kf ->
            kf.copy(
                poseOffsets = baseOffsets,
                expressionOverride = FacialExpression.HAPPY,
                handPoseOverride = HandPoseType.POINT
            )
        }
        val seededClip = seededProject.activeScene.clips.first { it.id == charClip.id }
        val midFrame = 10 + (legsAction.durationFrames * 0.5f).toInt()
        val beforeMid = KeyframeEvaluator.evaluate(seededClip, midFrame)
        val afterLegsInsert = com.example.engine.ModularActionEngine.insertModularActionIntoProject(
            project = seededProject,
            clipId = charClip.id,
            startGlobalFrame = 10,
            action = legsAction
        )
        val updatedClip = afterLegsInsert.activeScene.clips.first { it.id == charClip.id }
        val evalMid = KeyframeEvaluator.evaluate(updatedClip, midFrame)
        assertNotEquals(beforeMid.poseOffsets.rightLegOffset, evalMid.poseOffsets.rightLegOffset)
        assertEquals(beforeMid.poseOffsets.leftArmOffset, evalMid.poseOffsets.leftArmOffset, 0.05f)
        assertEquals(FacialExpression.HAPPY, evalMid.expressionOverride)
        assertEquals(HandPoseType.POINT, evalMid.handPoseOverride)
    }

    @Test
    fun `Phase 1 - simultaneous 5-track character layering blends Base Preset, Arm Gesture, IK Pose, Facial Overrides, and Auto Lip-Sync without overwriting`() {
        val clip = TimelineClip(
            id = "c_multi_1",
            layerId = "l_char",
            name = "Hero Multi",
            characterId = BuiltInCatalog.builtInCharacters.first().id,
            animationPresetType = AnimationPresetType.WALK,
            armGesturePreset = ArmGestureTrackPreset.WAVE,
            facialOverridesEnabled = true,
            mouthShapeOverride = MouthShapeOverride.SMILE,
            eyeShapeOverride = EyeShapeOverride.HAPPY_ARC,
            eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
            lipSyncAudioClipId = "aud_voice_1",
            keyframes = listOf(
                TransformKeyframe(
                    frame = 0,
                    poseOffsets = PoseKeyframeOffsets(
                        leftLegOffset = 14f,
                        rightArmOffset = -10f
                    )
                )
            )
        )
        val syncMeta = AudioSyncMetadata(
            clipId = "aud_voice_1",
            clipName = "Voice",
            fps = 24,
            phonemeMappings = listOf(
                PhonemeFrameMapping(frame = 12, timeMs = 500L, phoneme = "AA", mouthShape = "MTH4", amplitude = 0.72f)
            )
        )

        val evalAt12 = KeyframeEvaluator.evaluate(clip, 12)
        val blended = KeyframeEvaluator.evaluateSimultaneousCharacterTracks(
            clip = clip,
            eval = evalAt12,
            globalFrame = 12,
            fps = 24,
            audioSyncTracks = listOf(syncMeta)
        )

        // Track 1 + 3: Base leg IK preserved untouched by Wave gesture
        assertEquals(14f, blended.blendedPoseOffsets.leftLegOffset, 0.01f)
        // Track 2 + 3: Wave arm gesture added cleanly on top of IK (-10f)
        assertTrue("Expected Wave arm gesture to raise right arm strongly", blended.blendedPoseOffsets.rightArmOffset < -80f)
        // Track 4: Eye & Eyebrow overrides active
        assertEquals(EyeShapeOverride.HAPPY_ARC, blended.effectiveEyeOverride)
        assertEquals(EyebrowShapeOverride.ARCHED_HAPPY, blended.effectiveEyebrowOverride)
        // Track 5: Auto Lip-Sync overrides mouth to TALK_AA at speaking frame 12
        assertTrue(blended.lipSyncActive)
        assertEquals(MouthShapeOverride.TALK_AA, blended.effectiveMouthOverride)

        // Disabling Facial Expression Overrides bypasses eye/eyebrow/mouth overrides cleanly while keeping legs & arm gesture!
        val disabledClip = clip.copy(facialOverridesEnabled = false, lipSyncAudioClipId = null)
        val blendedDisabled = KeyframeEvaluator.evaluateSimultaneousCharacterTracks(
            clip = disabledClip,
            eval = KeyframeEvaluator.evaluate(disabledClip, 12),
            globalFrame = 12,
            fps = 24
        )
        assertEquals(MouthShapeOverride.AUTO, blendedDisabled.effectiveMouthOverride)
        assertEquals(EyeShapeOverride.AUTO, blendedDisabled.effectiveEyeOverride)
        assertEquals(EyebrowShapeOverride.AUTO, blendedDisabled.effectiveEyebrowOverride)
        assertTrue(blendedDisabled.blendedPoseOffsets.rightArmOffset < -80f)
    }

    @Test
    fun `Phase 2 - anatomical IK joint rotation constraints clamp extreme bends when enabled and allow cartoon bending when disabled`() {
        val extremePose = PoseKeyframeOffsets(
            headNodOffset = 95f,
            torsoTiltOffset = -85f,
            leftArmOffset = 220f,
            rightArmOffset = -220f,
            leftForearmOffset = 95f,     // backward left elbow hyperextension (anatomical is -142..0)
            rightForearmOffset = -95f,   // backward right elbow hyperextension (anatomical is 0..142)
            leftLegOffset = 160f,
            rightLegOffset = -160f,
            leftKneeOffset = -110f,      // backward knee hyperextension (anatomical is 0..132)
            rightKneeOffset = 190f       // over-bent/hyperextended knee
        )

        val clamped = com.example.engine.IkRigSolver.clampPoseOffsets(
            offsets = extremePose,
            enforceJointLimits = true,
            orientation = CharacterOrientation.FRONT
        )
        assertTrue("Neck should stay within anatomical limit", clamped.headNodOffset in -42f..42f)
        assertTrue("Torso should stay within anatomical limit", clamped.torsoTiltOffset in -38f..38f)
        assertTrue("Left elbow must not hyperextend past 0", clamped.leftForearmOffset in -142f..0f)
        assertTrue("Right elbow must not hyperextend below 0", clamped.rightForearmOffset in 0f..142f)
        assertTrue("Left knee must not invert backward", clamped.leftKneeOffset in 0f..132f)
        assertTrue("Right knee in FRONT view stays within hinge", clamped.rightKneeOffset in -132f..0f)
        assertTrue("Left hip should stay within anatomical limit", clamped.leftLegOffset in -105f..95f)

        val clampedSide = com.example.engine.IkRigSolver.clampPoseOffsets(
            offsets = extremePose,
            enforceJointLimits = true,
            orientation = CharacterOrientation.SIDE
        )
        assertTrue("Right knee in SIDE view clamps at 132 deg max", clampedSide.rightKneeOffset in 0f..132f)

        // When enforceJointLimits = false (cartoon bending mode), wider stylized range is permitted
        val freeCartoon = com.example.engine.IkRigSolver.clampPoseOffsets(
            offsets = extremePose,
            enforceJointLimits = false
        )
        assertTrue("Cartoon mode should allow wider head tilt", freeCartoon.headNodOffset > 42f)
        assertTrue("Cartoon mode should allow wider torso bend", freeCartoon.torsoTiltOffset < -38f)
        assertTrue("Cartoon mode should allow stylized knee inversion", freeCartoon.leftKneeOffset < -20f)

        // Verify live 2-bone IK drag solver respects enforceJointLimits flag
        val rig = BuiltInCatalog.builtInCharacters.first()
        val clip = TimelineClip(
            id = "c_ik_p2",
            layerId = "l_char",
            name = "IK Hero",
            characterId = rig.id
        )
        val eval = KeyframeEvaluator.evaluate(clip, 0)
        val basePose = ProceduralRigRenderer.computePose(clip.animationPresetType, 0f)
        val draggedClamped = com.example.engine.IkRigSolver.solveIkDragToOffsets(
            handleId = IkHandleId.HEAD,
            targetNx = 0.95f,
            targetNy = 0.95f,
            clip = clip,
            rig = rig,
            transform = eval,
            basePose = basePose,
            currentOffsets = PoseKeyframeOffsets(),
            stageWidth = 1000f,
            stageHeight = 1000f,
            enforceJointLimits = true
        )
        assertTrue("IK drag with limits ON clamps neck", draggedClamped.headNodOffset in -42f..42f)
        assertTrue("IK drag with limits ON clamps torso", draggedClamped.torsoTiltOffset in -38f..38f)
    }

    @Test
    fun `Phase 3 - deterministic spring-damper prop sway respects custom stiffness, damping, maxAngleDeg, and reproduces identically on scrub and export`() {
        val rig = BuiltInCatalog.builtInCharacters.first()
        val fastRunClip = TimelineClip(
            id = "c_sway_p3",
            layerId = "l_char",
            name = "Fast Lantern Sprint",
            characterId = rig.id,
            animationPresetType = AnimationPresetType.WALK,
            speedMultiplier = 1.6f,
            propType = CharacterPropType.LANTERN,
            propAttachment = PropAttachmentPoint.HEAD,
            propSwayEnabled = true,
            propSwayStrength = 1.4f,
            propSwayStiffness = 28f,
            propSwayDamping = 2.4f,
            propSwayMaxAngleDeg = 42f,
            keyframes = listOf(
                TransformKeyframe(frame = 0, x = 0.12f, y = 0.72f),
                TransformKeyframe(frame = 18, x = 0.88f, y = 0.72f)
            )
        )

        // Frame-reproducible determinism: jumping out of order gives exact bit-for-bit same state
        val f12a = com.example.engine.PropSwayPhysics.evaluate(fastRunClip, rig, frame = 12, fps = 24)
        com.example.engine.PropSwayPhysics.evaluate(fastRunClip, rig, frame = 3, fps = 24)
        val f12b = com.example.engine.PropSwayPhysics.evaluate(fastRunClip, rig, frame = 12, fps = 24)
        assertEquals(f12a, f12b)

        // Higher damping noticeably reduces peak oscillation compared to low damping
        val heavilyDamped = com.example.engine.PropSwayPhysics.evaluate(
            fastRunClip.copy(propSwayDamping = 13.5f), rig, frame = 12, fps = 24
        )
        assertTrue(
            "Heavier damping (${heavilyDamped.angleDeg}) should damp sway vs loose damping (${f12a.angleDeg})",
            kotlin.math.abs(heavilyDamped.angleDeg) < kotlin.math.abs(f12a.angleDeg)
        )

        // Custom maxAngleDeg strictly caps output angle
        val tightCap = com.example.engine.PropSwayPhysics.evaluate(
            fastRunClip.copy(propSwayMaxAngleDeg = 9f, propSwayStrength = 2.0f), rig, frame = 12, fps = 24
        )
        assertTrue("Custom maxAngleDeg=9 must clamp angle, got ${tightCap.angleDeg}", kotlin.math.abs(tightCap.angleDeg) <= 9.01f)

        // Moshi JSON round-trip preserves stiffness, damping, maxAngleDeg
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(TimelineClip::class.java)
        val restored = adapter.fromJson(adapter.toJson(fastRunClip))
        assertNotNull(restored)
        assertEquals(28f, restored!!.propSwayStiffness, 0.001f)
        assertEquals(2.4f, restored.propSwayDamping, 0.001f)
        assertEquals(42f, restored.propSwayMaxAngleDeg, 0.001f)
    }

    @Test
    fun `Phase 4 - 2_5D multi-plane parallax scales camera pan and zoom accurately for Far, Mid, Stage, and Near planes`() {
        val baseCam = com.example.engine.EvaluatedCamera(x = 0.70f, y = 0.40f, zoom = 1.50f, rotation = 10f)

        // Stage plane (1.0f) stays 100% identical to base camera
        val stageCam = com.example.engine.ParallaxCameraMath.computeLayerCamera(baseCam, ParallaxDepthPreset.STAGE.depth)
        assertEquals(baseCam.x, stageCam.x, 0.0001f)
        assertEquals(baseCam.y, stageCam.y, 0.0001f)
        assertEquals(baseCam.zoom, stageCam.zoom, 0.0001f)

        // Far plane (0.2f) drifts 5x slower and zooms gently
        val farCam = com.example.engine.ParallaxCameraMath.computeLayerCamera(baseCam, ParallaxDepthPreset.FAR.depth)
        assertEquals(0.54f, farCam.x, 0.001f)
        assertEquals(0.48f, farCam.y, 0.001f)
        assertEquals(1.10f, farCam.zoom, 0.001f)

        // Mid plane (0.6f) scales proportionally
        val midCam = com.example.engine.ParallaxCameraMath.computeLayerCamera(baseCam, ParallaxDepthPreset.MID.depth)
        assertEquals(0.62f, midCam.x, 0.001f)
        assertEquals(1.30f, midCam.zoom, 0.001f)

        // Near foreground plane (1.35f) sweeps past faster and looms larger than stage
        val nearCam = com.example.engine.ParallaxCameraMath.computeLayerCamera(baseCam, ParallaxDepthPreset.NEAR.depth)
        assertEquals(0.77f, nearCam.x, 0.001f)
        assertEquals(1.675f, nearCam.zoom, 0.001f)
        assertTrue(farCam.x < midCam.x && midCam.x < stageCam.x && stageCam.x < nearCam.x)

        // ProjectMutator.updateLayerParallaxDepth updates layer & Moshi round-trips it cleanly
        val proj = BuiltInCatalog.sceneTemplates.first().buildProject("Parallax Test")
        val targetLayer = proj.activeScene.layers.first { it.type != LayerType.AUDIO }
        val updated = ProjectMutator.updateLayerParallaxDepth(proj, targetLayer.id, ParallaxDepthPreset.FAR.depth)
        assertEquals(0.2f, updated.activeScene.layers.first { it.id == targetLayer.id }.parallaxDepth, 0.001f)

        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ProjectDocument::class.java)
        val restored = adapter.fromJson(adapter.toJson(updated))
        assertEquals(0.2f, restored!!.activeScene.layers.first { it.id == targetLayer.id }.parallaxDepth, 0.001f)
    }

    @Test
    fun `Phase 5 - deterministic offline weather engine evaluates all 7 styles identically per frame, respects wind angle and start-end time window`() {
        val activeStyles = WeatherParticleStyle.entries.filter { it != WeatherParticleStyle.NONE }
        assertEquals("Expected 7 weather styles", 7, activeStyles.size)

        for (style in activeStyles) {
            val cfg = WeatherParticleConfig(
                enabled = true,
                style = style,
                density = 0.65f,
                windSpeed = 1.1f,
                windAngleDeg = 22f,
                sizeScale = 1.2f,
                opacity = 0.85f,
                startSec = 1.0f,
                endSec = 4.0f,
                seed = 7711
            )
            // Outside time window -> zero particles
            assertTrue(
                "$style should be empty before startSec",
                com.example.engine.WeatherParticleEngine.sampleParticles(cfg, frameIndex = 10, fps = 24, width = 1280f, height = 720f).isEmpty()
            )
            assertTrue(
                "$style should be empty after endSec",
                com.example.engine.WeatherParticleEngine.sampleParticles(cfg, frameIndex = 120, fps = 24, width = 1280f, height = 720f).isEmpty()
            )

            // Inside window -> deterministic identical output on repeated calls
            val s1 = com.example.engine.WeatherParticleEngine.sampleParticles(cfg, frameIndex = 48, fps = 24, width = 1280f, height = 720f)
            val s2 = com.example.engine.WeatherParticleEngine.sampleParticles(cfg, frameIndex = 48, fps = 24, width = 1280f, height = 720f)
            assertTrue("$style must emit particles inside window", s1.isNotEmpty())
            assertEquals("$style must be 100% deterministic for same frame & seed", s1, s2)
        }

        // Changing wind angle measurably shifts rain X trajectory
        val calmRain = WeatherParticleConfig(enabled = true, style = WeatherParticleStyle.RAIN, windSpeed = 1.4f, windAngleDeg = 0f, seed = 42)
        val windyRain = calmRain.copy(windAngleDeg = 45f)
        val pCalm = com.example.engine.WeatherParticleEngine.sampleParticles(calmRain, frameIndex = 36, fps = 24, width = 1280f, height = 720f)
        val pWindy = com.example.engine.WeatherParticleEngine.sampleParticles(windyRain, frameIndex = 36, fps = 24, width = 1280f, height = 720f)
        assertNotEquals(pCalm.first().x, pWindy.first().x)

        // Moshi round-trip on AnimationScene.weatherLayers
        val scene = AnimationScene(weatherLayers = listOf(windyRain))
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(AnimationScene::class.java)
        val restored = adapter.fromJson(adapter.toJson(scene))
        assertEquals(1, restored!!.weatherLayers.size)
        assertEquals(WeatherParticleStyle.RAIN, restored.weatherLayers.first().style)
        assertEquals(45f, restored.weatherLayers.first().windAngleDeg, 0.001f)
    }

    @Test
    fun `Phase 6 - optional dialogue overlays default to OFF and preserve Odia, Hindi, and English Unicode text with pop-in and fade animations`() {
        val defaultBeat = DialogueOverlayBeat(
            speakerName = "Arjun",
            text = "ନମସ୍କାର! ଆଜି ବହୁତ ସୁନ୍ଦର ଦିନ।",
            language = "or",
            startFrame = 10,
            endFrame = 70
        )
        // Strictly OFF by default!
        assertFalse(defaultBeat.showSpeechBubble)
        assertFalse(defaultBeat.showSubtitle)
        assertFalse(defaultBeat.isOptedIn)
        assertTrue(
            "Default OFF overlay must never activate",
            com.example.engine.DialogueOverlayRenderer.selectActiveOverlays(listOf(defaultBeat), frame = 25).isEmpty()
        )

        // Opt-in Odia Speech Bubble + Hindi Subtitle + English Subtitle
        val odiaBubble = defaultBeat.copy(showSpeechBubble = true)
        val hindiSubtitle = DialogueOverlayBeat(
            speakerName = "Maya",
            text = "नमस्ते! आज बहुत सुंदर दिन है।",
            language = "hi",
            startFrame = 10,
            endFrame = 70,
            showSubtitle = true
        )
        val englishBoth = DialogueOverlayBeat(
            speakerName = "Hero",
            text = "Let's start the adventure!",
            language = "en",
            startFrame = 10,
            endFrame = 70,
            showSpeechBubble = true,
            showSubtitle = true
        )

        val active = com.example.engine.DialogueOverlayRenderer.selectActiveOverlays(
            listOf(defaultBeat, odiaBubble, hindiSubtitle, englishBoth),
            frame = 25
        )
        assertEquals(3, active.size)
        assertTrue(com.example.engine.DialogueOverlayRenderer.selectActiveOverlays(listOf(odiaBubble), frame = 5).isEmpty())
        assertTrue(com.example.engine.DialogueOverlayRenderer.selectActiveOverlays(listOf(odiaBubble), frame = 70).isEmpty())

        // Verify pop-in overshoot & settled scale + subtitle fade/slide
        val peakPop = (10..18).maxOf { f ->
            com.example.engine.DialogueOverlayRenderer.evaluateOverlayAnimation(odiaBubble, frame = f, fps = 24).bubbleScale
        }
        assertTrue("Expected comic pop-in overshoot > 1.0, got $peakPop", peakPop > 1.01f)
        val settled = com.example.engine.DialogueOverlayRenderer.evaluateOverlayAnimation(odiaBubble, frame = 40, fps = 24)
        assertEquals(1.0f, settled.bubbleScale, 0.01f)
        assertEquals(1.0f, settled.subtitleAlpha, 0.01f)
        assertEquals(0.0f, settled.subtitleSlideOffsetRatio, 0.01f)

        // Moshi JSON round-trip preserves exact Odia & Hindi Unicode strings
        val scene = AnimationScene(dialogueOverlays = listOf(odiaBubble, hindiSubtitle, englishBoth))
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(AnimationScene::class.java)
        val restored = adapter.fromJson(adapter.toJson(scene))
        assertEquals(3, restored!!.dialogueOverlays.size)
        assertEquals("ନମସ୍କାର! ଆଜି ବହୁତ ସୁନ୍ଦର ଦିନ।", restored.dialogueOverlays[0].text)
        assertEquals("or", restored.dialogueOverlays[0].language)
        assertEquals("नमस्ते! आज बहुत सुंदर दिन है।", restored.dialogueOverlays[1].text)
        assertEquals("hi", restored.dialogueOverlays[1].language)
        assertEquals("Let's start the adventure!", restored.dialogueOverlays[2].text)
    }

    @Test
    fun `Phase 7 - smart auto audio ducking smoothly attenuates BGM during active dialogue, recovers on release, and scales PCM16 audio`() {
        val bgmClip = TimelineClip(
            id = "aud_bgm",
            layerId = "l_audio",
            name = "Village Flute BGM",
            audioPath = "/tmp/bgm.mp3",
            audioTrackRole = AudioTrackRole.BGM,
            startFrame = 0,
            durationFrames = 144
        )
        val dialogueClip = TimelineClip(
            id = "aud_dlg",
            layerId = "l_audio",
            name = "Hero Dialogue",
            audioPath = "/tmp/voice.wav",
            audioTrackRole = AudioTrackRole.DIALOGUE,
            startFrame = 24, // 1.0s at 24fps
            durationFrames = 48 // 1.0s..3.0s
        )
        // Voice is loud from 1.0s..2.5s (local 0..1500ms), silent after 1600ms
        val syncMeta = AudioSyncMetadata(
            clipId = "aud_dlg",
            clipName = "Hero Dialogue",
            fps = 24,
            durationMs = 2000L,
            volumeCurve = listOf(
                VolumeCurvePoint(timeMs = 100L, frame = 2, gain = 0.65f),
                VolumeCurvePoint(timeMs = 600L, frame = 14, gain = 0.72f),
                VolumeCurvePoint(timeMs = 1200L, frame = 28, gain = 0.58f),
                VolumeCurvePoint(timeMs = 1700L, frame = 40, gain = 0.03f),
                VolumeCurvePoint(timeMs = 1950L, frame = 46, gain = 0.02f)
            )
        )
        val cfg = AudioDuckingConfig(
            duckingEnabled = true,
            duckTargetVolume = 0.25f,
            attackMs = 150,
            releaseMs = 350,
            voiceThreshold = 0.12f
        )

        val env = com.example.engine.AudioDuckingEngine.computeBgmGainEnvelope(
            sceneClips = listOf(bgmClip, dialogueClip),
            audioSyncTracks = listOf(syncMeta),
            config = cfg,
            totalDurationMs = 5000L,
            fps = 24
        )

        // Before dialogue starts (500ms) -> full volume 1.0f
        val gainBefore = com.example.engine.AudioDuckingEngine.sampleGainAtMs(env, 500L)
        assertEquals(1.0f, gainBefore, 0.02f)

        // Deep inside active dialogue (1800ms) -> smoothly ducked close to 0.25f
        val gainDuring = com.example.engine.AudioDuckingEngine.sampleGainAtMs(env, 1800L)
        assertTrue("Expected BGM ducked near 0.25f during speech, got $gainDuring", gainDuring in 0.24f..0.34f)

        // After dialogue finishes + release window (4400ms) -> smoothly recovered near 1.0f
        val gainAfter = com.example.engine.AudioDuckingEngine.sampleGainAtMs(env, 4400L)
        assertTrue("Expected BGM recovered near 1.0f after release, got $gainAfter", gainAfter > 0.92f)

        // When duckingEnabled = false -> BGM stays at 1.0f throughout dialogue
        val envOff = com.example.engine.AudioDuckingEngine.computeBgmGainEnvelope(
            sceneClips = listOf(bgmClip, dialogueClip),
            audioSyncTracks = listOf(syncMeta),
            config = cfg.copy(duckingEnabled = false),
            totalDurationMs = 5000L,
            fps = 24
        )
        assertEquals(1.0f, com.example.engine.AudioDuckingEngine.sampleGainAtMs(envOff, 1800L), 0.001f)

        // Verify PCM16 offline attenuation during dialogue window
        val rawBgmPcm = ShortArray(4410) { 20000.toShort() } // 100ms at 44.1kHz
        val duckedPcm = com.example.engine.AudioDuckingEngine.applyDuckingEnvelopeToPcm16(
            pcmSamples = rawBgmPcm,
            sampleRateHz = 44100,
            channelCount = 1,
            startTimeMs = 1800L,
            envelope = env
        )
        assertTrue("Ducked PCM should be attenuated ~75%, got ${duckedPcm[2000]}", duckedPcm[2000] in 4500..7000)

        // Moshi JSON round-trip of AudioDuckingConfig + TimelineClip.audioTrackRole
        val proj = ProjectDocument(
            name = "Ducking Roundtrip",
            audioDucking = cfg.copy(duckTargetVolume = 0.18f, attackMs = 120, releaseMs = 500)
        )
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ProjectDocument::class.java)
        val restored = adapter.fromJson(adapter.toJson(proj))
        assertEquals(0.18f, restored!!.audioDucking.duckTargetVolume, 0.001f)
        assertEquals(120, restored.audioDucking.attackMs)
        assertEquals(500, restored.audioDucking.releaseMs)
    }

    @Test
    fun `Phase 8 blueprint and director script dynamically wire all 7 pro features and preserve opt-in dialogue defaults`() {
        // 1. Dynamic StudioCapabilityRegistry + AIBlueprintExporter verification
        val caps = com.example.engine.StudioCapabilityRegistry.introspect()
        assertEquals(ArmGestureTrackPreset.entries.size, caps.armGesturePresets.size)
        assertEquals(SimultaneousTrackType.entries.size, caps.simultaneousTrackTypes.size)
        assertTrue(caps.ikJointLimits.containsKey("leftElbow"))
        assertTrue(caps.ikJointLimits.containsKey("neck"))
        assertEquals(ParallaxDepthPreset.entries.size, caps.parallaxDepthPresets.size)
        assertEquals(WeatherParticleStyle.entries.size, caps.weatherParticleStyles.size)
        assertEquals(DialogueLanguage.entries.size, caps.dialogueOverlayLanguages.size)
        assertEquals(AudioTrackRole.entries.size, caps.audioTrackRoles.size)
        assertEquals(0.25f, caps.audioDuckingDefaults.duckTargetVolume, 0.001f)

        val baseProject = BuiltInCatalog.sceneTemplates.first().buildProject("Phase 8 Full Pipeline")
        val bp = com.example.engine.AIBlueprintExporter.buildBlueprint(
            project = baseProject.copy(audioDucking = AudioDuckingConfig(duckTargetVolume = 0.22f)),
            characters = BuiltInCatalog.builtInCharacters.take(1),
            mediaAssets = emptyList()
        )
        assertEquals(0.22f, bp.projectMeta.audioDucking.duckTargetVolume, 0.001f)
        assertTrue(bp.capabilities.weatherParticleStyles.contains("FALLING_LEAVES"))
        assertTrue(bp.capabilities.dialogueOverlayLanguages.containsAll(listOf("en", "hi", "or")))
        assertTrue(bp.capabilities.armGesturePresets.contains("WAVE"))
        assertTrue(bp.capabilities.audioTrackRoles.containsAll(listOf("DIALOGUE", "BGM", "SFX")))
        assertEquals(45f, bp.capabilities.propSwayDefaults.stiffness, 0.01f)

        val heroId = BuiltInCatalog.builtInCharacters.first().id

        // 2. Director Script (.fcscript.json) exercising all 7 pro offline features simultaneously
        val scriptJson = """
            {
              "format": "framecraft.director_script",
              "schemaVersion": 3,
              "fps": 24,
              "audioDucking": {
                "duckingEnabled": true,
                "duckTargetVolume": 0.20,
                "attackMs": 140,
                "releaseMs": 380,
                "voiceThreshold": 0.10
              },
              "scenes": [
                {
                  "name": "Monsoon Village Scene",
                  "durationSec": 4.0,
                  "weatherLayers": [
                    { "style": "RAIN", "density": 0.85, "windSpeed": 1.1, "windAngleDeg": 18.0, "startSec": 0.5, "endSec": 3.5 },
                    { "style": "FALLING_LEAVES", "density": 0.45, "windSpeed": 0.7 }
                  ],
                  "dialogueOverlays": [
                    {
                      "id": "dlg_odia",
                      "speakerCharacterId": "$heroId",
                      "speakerClipId": "clip_hero",
                      "speakerName": "Arjun",
                      "text": "ନମସ୍କାର! ବର୍ଷା ଆସୁଛି।",
                      "language": "or",
                      "startSec": 0.5,
                      "endSec": 2.5,
                      "showSpeechBubble": true,
                      "showSubtitle": true
                    },
                    {
                      "id": "dlg_opt_out_default",
                      "speakerClipId": "clip_hero",
                      "text": "नमस्ते दुनिया",
                      "language": "hi",
                      "startSec": 2.5,
                      "endSec": 3.8
                    }
                  ],
                  "layers": [
                    { "id": "l_far", "name": "Far Mountains", "type": "IMAGE", "zIndex": 0, "parallaxDepth": 0.2 },
                    { "id": "l_stage", "name": "Hero Stage", "type": "CHARACTER", "zIndex": 1, "parallaxDepth": 1.0 },
                    { "id": "l_near", "name": "Near Grass", "type": "IMAGE", "zIndex": 2, "parallaxDepth": 1.35 },
                    { "id": "l_audio", "name": "Audio", "type": "AUDIO", "zIndex": 3 }
                  ],
                  "clips": [
                    {
                      "id": "clip_hero",
                      "layerId": "l_stage",
                      "name": "Hero",
                      "characterId": "$heroId",
                      "startSec": 0.0,
                      "durationSec": 4.0,
                      "animationPreset": "WALK",
                      "armGesturePreset": "WAVE",
                      "facialOverridesEnabled": true,
                      "mouthShapeOverride": "SMILE",
                      "enforceJointLimits": true,
                      "propType": "LANTERN",
                      "propAttachment": "RIGHT_HAND",
                      "propSwayEnabled": true,
                      "propSwayStiffness": 62.0,
                      "propSwayDamping": 7.5,
                      "propSwayMaxAngleDeg": 34.0,
                      "keyframes": [
                        {
                          "tSec": 0.0,
                          "x": 0.4,
                          "y": 0.82,
                          "armGesture": "POINT",
                          "ikPose": { "headNod": 95.0, "leftForearm": 60.0, "rightForearm": 210.0 }
                        }
                      ]
                    },
                    {
                      "id": "clip_voice",
                      "layerId": "l_audio",
                      "name": "Odia Voiceover",
                      "audioAssetId": "aud_voice_test",
                      "audioTrackRole": "DIALOGUE",
                      "audioVolume": 1.15,
                      "startSec": 0.5,
                      "durationSec": 2.0
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = com.example.engine.DirectorScriptReceiver.compileIntoProject(
            context = null,
            scriptJson = scriptJson,
            currentProject = baseProject,
            existingCharacters = BuiltInCatalog.builtInCharacters,
            existingMediaAssets = listOf(
                LocalMediaAsset(
                    id = "aud_voice_test",
                    name = "Odia Voiceover",
                    category = "Audio",
                    type = MediaAssetType.AUDIO,
                    localFilePath = "/tmp/odia_voice.wav"
                )
            ),
            importMode = com.example.engine.DirectorScriptImportMode.REPLACE_TIMELINE
        )
        assertTrue("Director Script failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val compiled = result.getOrThrow()

        // Dry-run report counts
        assertEquals(2, compiled.report.weatherLayerCount)
        assertEquals(2, compiled.report.dialogueOverlayCount)
        assertTrue(compiled.report.audioDuckingConfigured)

        // Phase 7 audio ducking + audio role
        assertEquals(0.20f, compiled.project.audioDucking.duckTargetVolume, 0.001f)
        assertEquals(140, compiled.project.audioDucking.attackMs)
        assertEquals(380, compiled.project.audioDucking.releaseMs)

        val scene = compiled.project.activeScene
        // Phase 4 parallax depths
        assertEquals(0.2f, scene.layers.first { it.id == "l_far" }.parallaxDepth, 0.001f)
        assertEquals(1.35f, scene.layers.first { it.id == "l_near" }.parallaxDepth, 0.001f)

        // Phase 5 weather layers
        assertEquals(2, scene.weatherLayers.size)
        assertEquals(WeatherParticleStyle.RAIN, scene.weatherLayers[0].style)
        assertEquals(0.85f, scene.weatherLayers[0].density, 0.001f)
        assertEquals(WeatherParticleStyle.FALLING_LEAVES, scene.weatherLayers[1].style)

        // Phase 6 dialogue overlays (Odia opted-in, Hindi defaults strictly OFF)
        assertEquals(2, scene.dialogueOverlays.size)
        val odiaBeat = scene.dialogueOverlays.first { it.id == "dlg_odia" }
        assertEquals("or", odiaBeat.language)
        assertTrue(odiaBeat.showSpeechBubble)
        assertTrue(odiaBeat.showSubtitle)
        assertTrue(odiaBeat.isOptedIn)

        val defaultOffBeat = scene.dialogueOverlays.first { it.id == "dlg_opt_out_default" }
        assertEquals("hi", defaultOffBeat.language)
        assertFalse("showSpeechBubble MUST default to false when omitted", defaultOffBeat.showSpeechBubble)
        assertFalse("showSubtitle MUST default to false when omitted", defaultOffBeat.showSubtitle)
        assertFalse("Dialogue overlay with omitted flags MUST NOT render", defaultOffBeat.isOptedIn)

        // Phase 1 + 2 + 3 character clip
        val heroClip = scene.clips.first { it.id == "clip_hero" }
        assertEquals(ArmGestureTrackPreset.WAVE, heroClip.armGesturePreset)
        assertTrue(heroClip.facialOverridesEnabled)
        assertEquals(MouthShapeOverride.SMILE, heroClip.mouthShapeOverride)
        assertEquals(62f, heroClip.propSwayStiffness, 0.01f)
        assertEquals(7.5f, heroClip.propSwayDamping, 0.01f)
        assertEquals(34f, heroClip.propSwayMaxAngleDeg, 0.01f)
        // Clamped IK limits (headNod <= 42, leftForearm <= 0, rightForearm <= 142)
        val kf0 = heroClip.keyframes.first()
        assertEquals(ArmGestureTrackPreset.POINT, kf0.armGestureOverride)
        assertTrue("Expected headNod clamped <= 42, got ${kf0.poseOffsets.headNodOffset}", kf0.poseOffsets.headNodOffset <= 42f)
        assertTrue("Expected leftForearm clamped <= 0, got ${kf0.poseOffsets.leftForearmOffset}", kf0.poseOffsets.leftForearmOffset <= 0f)
        assertTrue("Expected rightForearm clamped <= 142, got ${kf0.poseOffsets.rightForearmOffset}", kf0.poseOffsets.rightForearmOffset <= 142f)

        // Phase 7 audio clip role & volume
        val voiceClip = scene.clips.first { it.id == "clip_voice" }
        assertEquals(AudioTrackRole.DIALOGUE, voiceClip.audioTrackRole)
        assertEquals(1.15f, voiceClip.audioVolume, 0.01f)
    }

    @Test
    fun `Phase 8 multi-select bulk delete works atomically in PoseLibraryStorage and ProjectMutator timeline`() {
        // 1. PoseLibraryStorage bulk delete
        val tmpDir = java.nio.file.Files.createTempDirectory("fc_pose_bulk_test").toFile()
        try {
            val storageFile = java.io.File(tmpDir, "pose_library.json")
            val thumbsDir = java.io.File(tmpDir, "pose_thumbnails").apply { mkdirs() }
            val thumb1 = java.io.File(thumbsDir, "p1.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val thumb2 = java.io.File(thumbsDir, "p2.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val thumb3 = java.io.File(thumbsDir, "p3.png").apply { writeBytes(byteArrayOf(7, 8, 9)) }

            val storage = com.example.data.PoseLibraryStorage(storageFile, thumbsDir)
            storage.writePosesBlocking(
                listOf(
                    SavedPose(id = "p1", name = "Wave", category = PoseCategory.ACTION, thumbnailPath = thumb1.absolutePath),
                    SavedPose(id = "p2", name = "Sit", category = PoseCategory.SITTING, thumbnailPath = thumb2.absolutePath),
                    SavedPose(id = "p3", name = "Hero", category = PoseCategory.STANDING, thumbnailPath = thumb3.absolutePath)
                )
            )
            assertEquals(3, storage.loadPosesBlocking().size)

            // Bulk-delete p1 and p3 in one atomic operation
            val afterBulk = storage.deletePosesBlocking(setOf("p1", "p3"))
            assertEquals(1, afterBulk.size)
            assertEquals("p2", afterBulk.first().id)
            assertFalse("Thumb1 should be deleted", thumb1.exists())
            assertTrue("Thumb2 should remain", thumb2.exists())
            assertFalse("Thumb3 should be deleted", thumb3.exists())
            // Reload from disk to verify persistence
            assertEquals(listOf("p2"), storage.loadPosesBlocking().map { it.id })
        } finally {
            tmpDir.deleteRecursively()
        }

        // 2. ProjectMutator.deleteClips multi-select timeline bulk delete
        val layerUnlocked = AnimationLayer(id = "l1", name = "Chars", type = LayerType.CHARACTER, isLocked = false)
        val layerLocked = AnimationLayer(id = "l2", name = "Locked BG", type = LayerType.IMAGE, isLocked = true)
        val c1 = TimelineClip(id = "c1", layerId = "l1", name = "Hero 1", startFrame = 0, durationFrames = 48)
        val c2 = TimelineClip(id = "c2", layerId = "l1", name = "Hero 2", startFrame = 12, durationFrames = 48)
        val c3 = TimelineClip(id = "c3", layerId = "l1", name = "Hero 3", startFrame = 24, durationFrames = 48)
        val cLocked = TimelineClip(id = "c_locked", layerId = "l2", name = "Locked BG", startFrame = 0, durationFrames = 96)

        val proj = ProjectDocument(
            name = "Timeline Bulk Delete",
            scenes = listOf(
                AnimationScene(
                    name = "Scene 1",
                    layers = listOf(layerUnlocked, layerLocked),
                    clips = listOf(c1, c2, c3, cLocked),
                    cameraFocusClipId = "c2"
                )
            )
        )

        val updated = ProjectMutator.deleteClips(proj, setOf("c1", "c2", "c_locked"))
        val remainingIds = updated.activeScene.clips.map { it.id }.toSet()
        assertEquals(setOf("c3", "c_locked"), remainingIds)
        assertNull("cameraFocusClipId should be cleared when focused clip is bulk-deleted", updated.activeScene.cameraFocusClipId)
    }

    // ────────────────────────── UNIVERSAL ADVANCE KEYFRAME ENGINE TESTS ──────────────────────────

    @Test
    fun `universal keyframe engine evaluates cubic bezier hold spring elastic bounce and all loop modes accurately`() {
        // 1. Custom Cubic Bezier exact parametric verification: B(0.5) for (0.25, 0.1, 0.25, 1.0)
        val p1x = 0.25f; val p1y = 0.1f; val p2x = 0.25f; val p2y = 1.0f
        val uTrue = 0.5f
        val oneMinusU = 1f - uTrue
        val exactX = 3f * oneMinusU * oneMinusU * uTrue * p1x + 3f * oneMinusU * uTrue * uTrue * p2x + uTrue * uTrue * uTrue
        val exactY = 3f * oneMinusU * oneMinusU * uTrue * p1y + 3f * oneMinusU * uTrue * uTrue * p2y + uTrue * uTrue * uTrue
        val solvedY = com.example.engine.UniversalKeyframeEngine.evaluateCubicBezier(exactX, p1x, p1y, p2x, p2y)
        assertEquals("Cubic Bezier Newton-Raphson + bisection should match exact B_y(u)", exactY, solvedY, 0.001f)

        // 2. HOLD step curve: stays strictly at start value until exact next keyframe
        val holdTrack = UniversalKeyframeTrack(
            targetId = "clip_1",
            propertyPath = "transform.x",
            valueType = UniversalValueType.FLOAT,
            keyframes = listOf(
                UniversalKeyframe(frame = 0, floatValue = 0.2f, curve = UniversalCurveType.HOLD),
                UniversalKeyframe(frame = 10, floatValue = 0.8f, curve = UniversalCurveType.LINEAR)
            )
        )
        assertEquals(0.2f, com.example.engine.UniversalKeyframeEngine.evaluateFloat(holdTrack, 0), 0.0001f)
        assertEquals(0.2f, com.example.engine.UniversalKeyframeEngine.evaluateFloat(holdTrack, 9), 0.0001f)
        assertEquals(0.8f, com.example.engine.UniversalKeyframeEngine.evaluateFloat(holdTrack, 10), 0.0001f)

        // 3. Analytical damped SPRING: underdamped spring overshoots > 1.0 mid-flight and lands cleanly on 1.0 at t=1
        val spring0 = com.example.engine.UniversalKeyframeEngine.evaluateCurve(0f, UniversalCurveType.SPRING, springStiffness = 220f, springDamping = 8f)
        val springEnd = com.example.engine.UniversalKeyframeEngine.evaluateCurve(1f, UniversalCurveType.SPRING, springStiffness = 220f, springDamping = 8f)
        val springSamples = (1..20).map { i ->
            com.example.engine.UniversalKeyframeEngine.evaluateCurve(i / 20f, UniversalCurveType.SPRING, springStiffness = 220f, springDamping = 8f)
        }
        assertEquals(0f, spring0, 0.0001f)
        assertEquals(1f, springEnd, 0.0001f)
        assertTrue("Underdamped spring should exhibit realistic overshoot > 1.02", springSamples.max() > 1.02f)

        // 4. Loop modes: LOOP, PING_PONG, and OFFSET_REPEAT (infinite accumulation)
        val baseKfs = listOf(
            UniversalKeyframe(frame = 0, floatValue = 0f, curve = UniversalCurveType.LINEAR),
            UniversalKeyframe(frame = 10, floatValue = 10f, curve = UniversalCurveType.LINEAR)
        )
        val loopTrack = UniversalKeyframeTrack(targetId = "t", propertyPath = "p", loopMode = UniversalLoopMode.LOOP, keyframes = baseKfs)
        assertEquals(5f, com.example.engine.UniversalKeyframeEngine.evaluateFloat(loopTrack, 15), 0.01f)
        assertEquals(2f, com.example.engine.UniversalKeyframeEngine.evaluateFloat(loopTrack, 22), 0.01f)

        val pingPongTrack = UniversalKeyframeTrack(targetId = "t", propertyPath = "p", loopMode = UniversalLoopMode.PING_PONG, keyframes = baseKfs)
        assertEquals(7f, com.example.engine.UniversalKeyframeEngine.evaluateFloat(pingPongTrack, 13), 0.01f)
        assertEquals(3f, com.example.engine.UniversalKeyframeEngine.evaluateFloat(pingPongTrack, 23), 0.01f)

        val offsetRepeatTrack = UniversalKeyframeTrack(targetId = "bg", propertyPath = "bg.scrollX", loopMode = UniversalLoopMode.OFFSET_REPEAT, keyframes = baseKfs)
        assertEquals(15f, com.example.engine.UniversalKeyframeEngine.evaluateFloat(offsetRepeatTrack, 15), 0.01f)
        assertEquals(34f, com.example.engine.UniversalKeyframeEngine.evaluateFloat(offsetRepeatTrack, 34), 0.01f)
    }

    @Test
    fun `universal keyframe engine evaluates curved bezier motionPath with autoOrientToPath tangent angles`() {
        // Parabolic arc rising from (0.1, 0.8) through peak (0.5, 0.2) down to (0.9, 0.8)
        val pathTrack = UniversalKeyframeTrack(
            targetId = "clip_bird",
            propertyPath = "transform.position",
            domain = UniversalPropertyDomain.CHARACTER,
            valueType = UniversalValueType.VEC2,
            motionPath = UniversalMotionPathSpec(
                controlPoints = listOf(
                    NormalizedPoint(0.1f, 0.8f),
                    NormalizedPoint(0.5f, 0.2f),
                    NormalizedPoint(0.9f, 0.8f)
                ),
                autoOrientToPath = true,
                orientOffsetDeg = 0f
            ),
            keyframes = listOf(
                UniversalKeyframe(frame = 0, vec2X = 0.1f, vec2Y = 0.8f, curve = UniversalCurveType.LINEAR),
                UniversalKeyframe(frame = 20, vec2X = 0.9f, vec2Y = 0.8f, curve = UniversalCurveType.LINEAR)
            )
        )

        val startEval = com.example.engine.UniversalKeyframeEngine.evaluateTrack(pathTrack, 2)!!
        val peakEval = com.example.engine.UniversalKeyframeEngine.evaluateTrack(pathTrack, 10)!!
        val descendEval = com.example.engine.UniversalKeyframeEngine.evaluateTrack(pathTrack, 18)!!

        // At peak (u=0.5), Bezier midpoint y = 0.25*0.8 + 0.5*0.2 + 0.25*0.8 = 0.50, tangent is horizontal (0 deg)
        assertEquals(0.50f, peakEval.vec2X!!, 0.01f)
        assertEquals(0.50f, peakEval.vec2Y!!, 0.01f)
        assertNotNull(peakEval.autoOrientDeg)
        assertEquals(0f, peakEval.autoOrientDeg!!, 1.5f)

        // Ascending tangent points up-right (negative angle in screen coords y-down)
        assertTrue("Ascending bird should tilt upward (< -20deg), got ${startEval.autoOrientDeg}", startEval.autoOrientDeg!! < -20f)
        // Descending tangent points down-right (positive angle in screen coords y-down)
        assertTrue("Descending bird should tilt downward (> 20deg), got ${descendEval.autoOrientDeg}", descendEval.autoOrientDeg!! > 20f)
    }

    @Test
    fun `universal keyframe engine resolves 2_5D parallax depth seamless background scroll blur and colour grading`() {
        val layerFar = AnimationLayer(id = "layer_sky", name = "Far Sky", type = LayerType.BACKGROUND, parallaxDepth = 0.25f)
        val tracks = listOf(
            UniversalKeyframeTrack(
                targetId = "layer_sky",
                propertyPath = "bg.parallaxDepth",
                domain = UniversalPropertyDomain.BACKGROUND_PARALLAX,
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, floatValue = 0.15f, curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 20, floatValue = 1.35f, curve = UniversalCurveType.LINEAR)
                )
            ),
            UniversalKeyframeTrack(
                targetId = "background",
                propertyPath = "bg.scrollX",
                domain = UniversalPropertyDomain.BACKGROUND_PARALLAX,
                loopMode = UniversalLoopMode.OFFSET_REPEAT,
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, floatValue = 0f, curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 24, floatValue = 1f, curve = UniversalCurveType.LINEAR)
                )
            ),
            UniversalKeyframeTrack(
                targetId = "background",
                propertyPath = "bg.blur",
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, floatValue = 0f, curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 20, floatValue = 0.8f, curve = UniversalCurveType.LINEAR)
                )
            ),
            UniversalKeyframeTrack(
                targetId = "background",
                propertyPath = "bg.dayNightBlend",
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, floatValue = 0f, curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 20, floatValue = 1f, curve = UniversalCurveType.LINEAR)
                )
            ),
            UniversalKeyframeTrack(
                targetId = "background",
                propertyPath = "bg.ambientLightColor",
                valueType = UniversalValueType.COLOR,
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, colorHex = "#FF000000", curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 20, colorHex = "#FFFF8040", curve = UniversalCurveType.LINEAR)
                )
            ),
            UniversalKeyframeTrack(
                targetId = "scene",
                propertyPath = "colourGrading.exposure",
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, floatValue = -0.4f, curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 20, floatValue = 0.4f, curve = UniversalCurveType.LINEAR)
                )
            ),
            UniversalKeyframeTrack(
                targetId = "scene",
                propertyPath = "colourGrading.temperature",
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, floatValue = -0.5f, curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 20, floatValue = 0.5f, curve = UniversalCurveType.LINEAR)
                )
            ),
            UniversalKeyframeTrack(
                targetId = "scene",
                propertyPath = "colourGrading.vignette",
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, floatValue = 0.2f, curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 20, floatValue = 0.8f, curve = UniversalCurveType.LINEAR)
                )
            )
        )

        val snap10 = com.example.engine.UniversalKeyframeEngine.evaluateAllTracks(tracks, frameIndex = 10)
        assertEquals(0.75f, com.example.engine.UniversalKeyframeEngine.resolveLayerParallaxDepth(snap10, layerFar), 0.01f)

        val grading10 = com.example.engine.UniversalKeyframeEngine.resolveBackgroundAndGrading(snap10)
        assertEquals(0.4f, grading10.bgDepthBlur, 0.01f)
        assertEquals(0.5f, grading10.dayNightBlend, 0.01f)
        assertEquals(0.0f, grading10.exposure, 0.01f)
        assertEquals(0.0f, grading10.temperature, 0.01f)
        assertEquals(0.5f, grading10.vignette, 0.01f)
        assertNotNull(grading10.ambientLightColorArgb)
        assertTrue(grading10.hasBackgroundTransform)
        assertTrue(grading10.hasColorGrading)

        // Verify OFFSET_REPEAT infinite scrolling at frame 60 (2.5 full background wraps)
        val snap60 = com.example.engine.UniversalKeyframeEngine.evaluateAllTracks(tracks, frameIndex = 60)
        val grading60 = com.example.engine.UniversalKeyframeEngine.resolveBackgroundAndGrading(snap60)
        assertEquals(2.5f, grading60.bgScrollX, 0.02f)
    }

    @Test
    fun `universal keyframe engine layers full character overrides cleanly over manual KeyframeEvaluator tracks`() {
        val clip = TimelineClip(
            id = "clip_hero",
            layerId = "layer_char",
            name = "Hero",
            startFrame = 0,
            durationFrames = 60,
            characterId = "char_hero",
            animationPresetType = AnimationPresetType.IDLE,
            orientation = CharacterOrientation.FRONT,
            keyframes = listOf(
                TransformKeyframe(frame = 0, x = 0.25f, y = 0.82f, rotation = 0f),
                TransformKeyframe(frame = 30, x = 0.55f, y = 0.82f, rotation = 0f)
            )
        )

        val tracks = listOf(
            UniversalKeyframeTrack(
                targetId = "clip_hero",
                propertyPath = "character.preset",
                valueType = UniversalValueType.STRING,
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, stringValue = "WALK"),
                    UniversalKeyframe(frame = 15, stringValue = "FLYING")
                )
            ),
            UniversalKeyframeTrack(
                targetId = "clip_hero",
                propertyPath = "character.orientation",
                valueType = UniversalValueType.STRING,
                keyframes = listOf(UniversalKeyframe(frame = 0, stringValue = "THREE_QUARTER_FRONT"))
            ),
            UniversalKeyframeTrack(
                targetId = "clip_hero",
                propertyPath = "expression.mouth",
                valueType = UniversalValueType.STRING,
                keyframes = listOf(UniversalKeyframe(frame = 0, stringValue = "SHOCK_O"))
            ),
            UniversalKeyframeTrack(
                targetId = "clip_hero",
                propertyPath = "character.gazeTarget",
                valueType = UniversalValueType.VEC2,
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, vec2X = -0.6f, vec2Y = 0.0f, curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 20, vec2X = 0.6f, vec2Y = -0.4f, curve = UniversalCurveType.LINEAR)
                )
            ),
            UniversalKeyframeTrack(
                targetId = "clip_hero",
                propertyPath = "character.headTilt",
                keyframes = listOf(UniversalKeyframe(frame = 0, floatValue = 12f))
            ),
            UniversalKeyframeTrack(
                targetId = "clip_hero",
                propertyPath = "ik.leftElbow",
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, floatValue = 0f, curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 20, floatValue = -60f, curve = UniversalCurveType.LINEAR)
                )
            ),
            UniversalKeyframeTrack(
                targetId = "clip_hero",
                propertyPath = "propSway.stiffness",
                keyframes = listOf(UniversalKeyframe(frame = 0, floatValue = 88f))
            ),
            UniversalKeyframeTrack(
                targetId = "clip_hero",
                propertyPath = "transform.flipX",
                valueType = UniversalValueType.BOOLEAN,
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, boolValue = false),
                    UniversalKeyframe(frame = 15, boolValue = true)
                )
            ),
            UniversalKeyframeTrack(
                targetId = "clip_hero",
                propertyPath = "character.tint",
                valueType = UniversalValueType.COLOR,
                keyframes = listOf(UniversalKeyframe(frame = 0, colorHex = "#80FFAA00"))
            )
        )

        val frame = 20
        val baseEval = KeyframeEvaluator.evaluate(clip, frame)
        val baseMulti = KeyframeEvaluator.evaluateSimultaneousCharacterTracks(clip, baseEval, frame, 24)
        val snap = com.example.engine.UniversalKeyframeEngine.evaluateAllTracks(tracks, frame)
        val resolved = com.example.engine.UniversalKeyframeEngine.resolveCharacterOverrides(snap, clip, baseEval, baseMulti)

        // Existing manual X/Y position remains intact when not overridden by universal tracks!
        assertEquals(baseEval.x, resolved.transform.x, 0.001f)
        assertEquals(AnimationPresetType.FLYING, resolved.animationPreset)
        assertEquals(CharacterOrientation.THREE_QUARTER_FRONT, resolved.orientation)
        assertEquals(MouthShapeOverride.SHOCK_O, resolved.mouthOverride)
        assertTrue(resolved.flipHorizontal)
        assertEquals(0.6f, resolved.gazeTargetX!!, 0.01f)
        assertEquals(-0.4f, resolved.gazeTargetY!!, 0.01f)
        assertEquals(12f, resolved.poseOffsets.headNodOffset, 0.1f)
        assertEquals(-60f, resolved.poseOffsets.leftForearmOffset, 0.1f)
        assertEquals(88f, resolved.propSwayConfig.stiffness, 0.1f)
        assertEquals(0x80FFAA00.toInt(), resolved.tintArgb)
    }

    @Test
    fun `universal keyframe engine animates inline SVG sub-parts custom transitions masks and VFX weather deterministically`() {
        val tracks = listOf(
            // Inline SVG sub-part via "svg.<nodeId>.rotation"
            UniversalKeyframeTrack(
                targetId = "clip_tree",
                propertyPath = "svg.tree_canopy.rotation",
                domain = UniversalPropertyDomain.INLINE_SVG,
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, floatValue = -10f, curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 20, floatValue = 10f, curve = UniversalCurveType.LINEAR)
                )
            ),
            // Inline SVG sub-part via direct node targetId
            UniversalKeyframeTrack(
                targetId = "bird_wing_l",
                propertyPath = "rotation",
                domain = UniversalPropertyDomain.INLINE_SVG,
                loopMode = UniversalLoopMode.PING_PONG,
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, floatValue = -35f, curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 6, floatValue = 35f, curve = UniversalCurveType.LINEAR)
                )
            ),
            // Custom Transition overrides
            UniversalKeyframeTrack(
                targetId = "whip_iris",
                propertyPath = "transition.progress",
                domain = UniversalPropertyDomain.TRANSITION,
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, floatValue = 0f, curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 20, floatValue = 1f, curve = UniversalCurveType.LINEAR)
                )
            ),
            UniversalKeyframeTrack(
                targetId = "whip_iris",
                propertyPath = "transition.wipeAngle",
                keyframes = listOf(UniversalKeyframe(frame = 0, floatValue = 45f))
            ),
            UniversalKeyframeTrack(
                targetId = "whip_iris",
                propertyPath = "transition.flashAlpha",
                keyframes = listOf(UniversalKeyframe(frame = 0, floatValue = 0.65f))
            ),
            // Weather & VFX overrides
            UniversalKeyframeTrack(
                targetId = "weather",
                propertyPath = "weather.density",
                keyframes = listOf(
                    UniversalKeyframe(frame = 0, floatValue = 0.2f, curve = UniversalCurveType.LINEAR),
                    UniversalKeyframe(frame = 20, floatValue = 0.9f, curve = UniversalCurveType.LINEAR)
                )
            ),
            UniversalKeyframeTrack(
                targetId = "weather",
                propertyPath = "weather.windSpeed",
                keyframes = listOf(UniversalKeyframe(frame = 0, floatValue = 1.8f))
            )
        )

        val snap10 = com.example.engine.UniversalKeyframeEngine.evaluateAllTracks(tracks, 10)
        val baseNodes = mapOf("tree_canopy" to com.example.engine.EvaluatedNodeTransform(rotation = 5f))
        val mergedNodes = com.example.engine.UniversalKeyframeEngine.resolveInlineSvgNodeTransforms(
            snapshot = snap10,
            clipId = "clip_tree",
            inlineSvgAssetId = "svg_tree",
            knownNodeIds = listOf("tree_canopy", "bird_wing_l"),
            baseNodeTransforms = baseNodes
        )
        // At frame 10, tree_canopy universal rotation is 0f + base 5f = 5f
        assertEquals(5f, mergedNodes["tree_canopy"]!!.rotation, 0.1f)
        // At frame 10 (PING_PONG period 6: cycle 1 reversed at local 4 -> frame 2), bird_wing_l = -35 + (2/6)*70 = -11.67
        assertEquals(-11.67f, mergedNodes["bird_wing_l"]!!.rotation, 0.5f)

        // Custom transition evaluation with keyframed progress + flashAlpha override
        val tOverrides = com.example.engine.UniversalKeyframeEngine.resolveTransitionOverrides(snap10, "whip_iris", isEntering = true)
        assertEquals(0.5f, tOverrides.progressOverride!!, 0.01f)
        assertEquals(45f, tOverrides.wipeAngleOverrideDeg!!, 0.01f)
        assertEquals(0.65f, tOverrides.flashAlphaOverride!!, 0.01f)
        val transDef = CustomTransitionDef(id = "whip_iris", clipPathType = CustomTransitionClipType.WIPE_ANGLE)
        val evalTrans = com.example.engine.UniversalCustomTransitionEngine.evaluate(
            def = transDef,
            rawProgress = tOverrides.progressOverride!!,
            flashAlphaOverride = tOverrides.flashAlphaOverride
        )
        assertEquals(0.65f, evalTrans.flashAlpha, 0.01f)

        // Weather override evaluation
        val (_, weatherLayers) = com.example.engine.UniversalKeyframeEngine.resolveVfxAndWeather(
            snapshot = snap10,
            baseVfx = SceneVfxConfig(),
            baseWeatherLayers = listOf(WeatherParticleConfig(enabled = true, style = WeatherParticleStyle.RAIN, density = 0.1f))
        )
        assertEquals(0.55f, weatherLayers.first().density, 0.01f)
        assertEquals(1.8f, weatherLayers.first().windSpeed, 0.01f)
    }

    @Test
    fun `studio capability registry blueprint exporter and director script receiver compile universal keyframeTracks end to end`() {
        // 1. Dynamic Registry & Blueprint v3 exposure
        val caps = com.example.engine.StudioCapabilityRegistry.introspect()
        assertEquals(UniversalCurveType.entries.size, caps.universalCurveTypes.size)
        assertEquals(UniversalLoopMode.entries.size, caps.universalLoopModes.size)
        assertTrue(caps.universalKeyframeChannels.any { it.id == "bg.parallaxDepth" })
        assertTrue(caps.universalKeyframeChannels.any { it.id == "svg.<nodeId>.rotation" })

        val baseProject = BuiltInCatalog.sceneTemplates.first().buildProject("Universal Blueprint Test")
        val blueprintJson = com.example.engine.AIBlueprintExporter.exportToJson(
            project = baseProject,
            characters = BuiltInCatalog.builtInCharacters.take(2),
            mediaAssets = emptyList()
        )
        assertTrue(blueprintJson.contains("\"universalCurveTypes\""))
        assertTrue(blueprintJson.contains("\"OFFSET_REPEAT\""))
        assertTrue(blueprintJson.contains("\"universalKeyframeChannels\""))
        assertTrue(blueprintJson.contains("\"universalKeyframeTrackExample\""))

        // 2. Director Script Receiver compiles keyframeTracks[] into AnimationScene.universalKeyframeTracks
        val scriptJson = """
            {
              "format": "framecraft.director_script",
              "schemaVersion": 3,
              "fps": 24,
              "scenes": [
                {
                  "name": "Parallax Sky Scene",
                  "durationSec": 4.0,
                  "keyframeTracks": [
                    {
                      "targetId": "background",
                      "propertyPath": "bg.scrollX",
                      "loopMode": "OFFSET_REPEAT",
                      "keyframes": [
                        { "tSec": 0.0, "value": 0.0, "curve": "LINEAR" },
                        { "tSec": 2.0, "value": 1.0, "curve": "LINEAR" }
                      ]
                    },
                    {
                      "targetId": "clip_hero",
                      "propertyPath": "transform.position",
                      "motionPath": {
                        "controlPoints": [
                          { "x": 0.1, "y": 0.8 },
                          { "x": 0.5, "y": 0.3 },
                          { "x": 0.9, "y": 0.8 }
                        ],
                        "autoOrientToPath": true
                      },
                      "keyframes": [
                        { "tSec": 0.0, "vec2": [0.1, 0.8], "cubicBezier": [0.22, 1.0, 0.36, 1.0] },
                        { "tSec": 2.0, "vec2": [0.9, 0.8], "curve": "SPRING", "springStiffness": 200.0, "springDamping": 12.0 }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val (_, dryRun) = com.example.engine.DirectorScriptReceiver.validateAndDryRun(
            scriptJson = scriptJson,
            existingCharacters = BuiltInCatalog.builtInCharacters,
            existingMediaAssets = emptyList()
        )
        assertTrue("Director script with keyframeTracks should validate cleanly", dryRun.isValid)
        assertEquals(2, dryRun.universalKeyframeTrackCount)
        assertEquals(4, dryRun.universalKeyframeCount)

        val compiled = com.example.engine.DirectorScriptReceiver.compileIntoProject(
            context = null,
            scriptJson = scriptJson,
            currentProject = baseProject,
            existingCharacters = BuiltInCatalog.builtInCharacters,
            existingMediaAssets = emptyList(),
            importMode = com.example.engine.DirectorScriptImportMode.REPLACE_TIMELINE
        ).getOrThrow()

        val compiledTracks = compiled.project.activeScene.universalKeyframeTracks
        assertEquals(2, compiledTracks.size)
        assertEquals(UniversalLoopMode.OFFSET_REPEAT, compiledTracks[0].loopMode)
        assertEquals(UniversalPropertyDomain.BACKGROUND_PARALLAX, compiledTracks[0].domain)
        assertTrue(compiledTracks[1].motionPath?.autoOrientToPath == true)
        assertEquals(UniversalCurveType.CUSTOM_CUBIC, compiledTracks[1].keyframes[0].curve)
        assertEquals(UniversalCurveType.SPRING, compiledTracks[1].keyframes[1].curve)

        // 3. Full Moshi JSON round-trip persistence verification
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ProjectDocument::class.java)
        val restored = adapter.fromJson(adapter.toJson(compiled.project))
        assertNotNull(restored)
        assertEquals(2, restored!!.activeScene.universalKeyframeTracks.size)
        assertEquals("bg.scrollX", restored.activeScene.universalKeyframeTracks[0].propertyPath)
        assertEquals(UniversalLoopMode.OFFSET_REPEAT, restored.activeScene.universalKeyframeTracks[0].loopMode)
    }

    @Test
    fun `layer Z-order actions and lock-safe delete update orderIndex, clips, focus, and blueprint sync`() {
        val layerBg = AnimationLayer(id = "l_bg", name = "Background", type = LayerType.BACKGROUND, orderIndex = 0)
        val layerHero = AnimationLayer(id = "l_hero", name = "Hero", type = LayerType.CHARACTER, orderIndex = 1)
        val layerProp = AnimationLayer(id = "l_prop", name = "Prop", type = LayerType.SHAPE, orderIndex = 2, isLocked = true)
        val layerText = AnimationLayer(id = "l_text", name = "Caption", type = LayerType.TEXT, orderIndex = 3)

        val clipHero = TimelineClip(id = "c_hero", layerId = "l_hero", name = "Hero Clip")
        val clipProp = TimelineClip(id = "c_prop", layerId = "l_prop", name = "Prop Clip")
        val clipText = TimelineClip(id = "c_text", layerId = "l_text", name = "Caption Clip")

        val scene = AnimationScene(
            id = "s_zorder",
            name = "Z-Order Scene",
            layers = listOf(layerBg, layerHero, layerProp, layerText),
            clips = listOf(clipHero, clipProp, clipText),
            cameraFocusClipId = "c_text",
            universalKeyframeTracks = listOf(
                UniversalKeyframeTrack(
                    id = "trk_text",
                    targetId = "c_text",
                    propertyPath = "transform.opacity",
                    domain = UniversalPropertyDomain.CHARACTER,
                    valueType = UniversalValueType.FLOAT,
                    keyframes = listOf(UniversalKeyframe(frame = 0, floatValue = 1f))
                )
            )
        )
        val project = ProjectDocument(name = "ZOrder Test", scenes = listOf(scene))

        // 1. Bring Background to front -> order becomes [l_hero, l_prop, l_text, l_bg]
        val front = ProjectMutator.bringLayerToFront(project, "l_bg")
        assertEquals(listOf("l_hero", "l_prop", "l_text", "l_bg"), front.activeScene.layers.sortedBy { it.orderIndex }.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), front.activeScene.layers.sortedBy { it.orderIndex }.map { it.orderIndex })
        // Already at front -> no-op (exact equality preserves undo cleanliness)
        assertEquals(front, ProjectMutator.bringLayerToFront(front, "l_bg"))

        // 2. Send Background back to back -> order restored [l_bg, l_hero, l_prop, l_text]
        val back = ProjectMutator.sendLayerToBack(front, "l_bg")
        assertEquals(listOf("l_bg", "l_hero", "l_prop", "l_text"), back.activeScene.layers.sortedBy { it.orderIndex }.map { it.id })
        // Already at back -> no-op
        assertEquals(back, ProjectMutator.sendLayerToBack(back, "l_bg"))

        // 3. Bring Forward 1 step on Hero -> swaps with Prop: [l_bg, l_prop, l_hero, l_text]
        val forward = ProjectMutator.bringLayerForward(back, "l_hero")
        assertEquals(listOf("l_bg", "l_prop", "l_hero", "l_text"), forward.activeScene.layers.sortedBy { it.orderIndex }.map { it.id })

        // 4. Send Backward 1 step on Hero -> returns to [l_bg, l_hero, l_prop, l_text]
        val backward = ProjectMutator.sendLayerBackward(forward, "l_hero")
        assertEquals(listOf("l_bg", "l_hero", "l_prop", "l_text"), backward.activeScene.layers.sortedBy { it.orderIndex }.map { it.id })

        // 5. Blueprint export reflects live Z-order immediately
        val bp = com.example.engine.AIBlueprintExporter.buildBlueprint(
            project = forward,
            characters = emptyList(),
            mediaAssets = emptyList()
        )
        assertEquals(listOf("l_bg", "l_prop", "l_hero", "l_text"), bp.projectAssets.activeSceneLayers.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), bp.projectAssets.activeSceneLayers.map { it.zIndex })

        // 6. Locked layer cannot be deleted
        val afterLockedDelete = ProjectMutator.deleteLayer(backward, "l_prop")
        assertEquals(backward, afterLockedDelete)
        assertEquals(4, afterLockedDelete.activeScene.layers.size)

        // 7. Unlocked layer deletes cleanly with clips, camera focus, universal tracks, and contiguous reindexing
        val afterDelete = ProjectMutator.deleteLayer(backward, "l_text")
        assertEquals(listOf("l_bg", "l_hero", "l_prop"), afterDelete.activeScene.layers.sortedBy { it.orderIndex }.map { it.id })
        assertEquals(listOf(0, 1, 2), afterDelete.activeScene.layers.sortedBy { it.orderIndex }.map { it.orderIndex })
        assertFalse(afterDelete.activeScene.clips.any { it.layerId == "l_text" })
        assertNull(afterDelete.activeScene.cameraFocusClipId)
        assertTrue(afterDelete.activeScene.universalKeyframeTracks.isEmpty())

        // 8. Cannot delete last remaining layer
        val singleLayerProject = ProjectDocument(
            name = "Single Layer",
            scenes = listOf(AnimationScene(layers = listOf(layerBg)))
        )
        assertEquals(singleLayerProject, ProjectMutator.deleteLayer(singleLayerProject, "l_bg"))
    }

    @Test
    fun `camera box aspect ratio stays locked across all 4 corners, 4 edges, and project ratios`() {
        val ratios = listOf(
            "16:9" to (1280f to 720f),
            "9:16" to (720f to 1280f),
            "1:1" to (1080f to 1080f),
            "4:5" to (1080f to 1350f)
        )
        val resizeHandles = listOf(
            com.example.engine.CameraBoxHandle.TOP_LEFT,
            com.example.engine.CameraBoxHandle.TOP_RIGHT,
            com.example.engine.CameraBoxHandle.BOTTOM_LEFT,
            com.example.engine.CameraBoxHandle.BOTTOM_RIGHT,
            com.example.engine.CameraBoxHandle.EDGE_TOP,
            com.example.engine.CameraBoxHandle.EDGE_BOTTOM,
            com.example.engine.CameraBoxHandle.EDGE_LEFT,
            com.example.engine.CameraBoxHandle.EDGE_RIGHT
        )

        val baseBox = CameraBoxClip(
            id = "cam_a",
            label = "A",
            startFrame = 0,
            endFrame = 48,
            centerX = 0.5f,
            centerY = 0.5f,
            zoom = 1.25f,
            rotation = 12f
        )

        for ((ratioLabel, dims) in ratios) {
            val (w, h) = dims
            val expectedAspect = w / h
            val initialGeom = com.example.engine.CameraBoxEngine.computeBoxGeometry(baseBox, w, h)
            assertEquals("Initial aspect mismatch for $ratioLabel", expectedAspect, initialGeom.aspectRatio, 0.002f)

            for (handle in resizeHandles) {
                // Drag inward (zoom in) and outward (zoom out) along arbitrary off-axis vectors
                val zoomedIn = com.example.engine.CameraBoxEngine.applyAspectLockedResize(
                    box = baseBox,
                    handle = handle,
                    pointerPxX = initialGeom.centerX + 42f,
                    pointerPxY = initialGeom.centerY - 28f,
                    canvasWidth = w,
                    canvasHeight = h
                )
                val geomIn = com.example.engine.CameraBoxEngine.computeBoxGeometry(zoomedIn, w, h)
                assertEquals(
                    "Zoom-in aspect lock broken on $ratioLabel handle=$handle",
                    expectedAspect,
                    geomIn.aspectRatio,
                    0.002f
                )
                assertTrue(zoomedIn.zoom in com.example.engine.CameraBoxEngine.MIN_ZOOM..com.example.engine.CameraBoxEngine.MAX_ZOOM)

                val zoomedOut = com.example.engine.CameraBoxEngine.applyAspectLockedResize(
                    box = baseBox,
                    handle = handle,
                    pointerPxX = initialGeom.centerX + w * 0.45f,
                    pointerPxY = initialGeom.centerY + h * 0.45f,
                    canvasWidth = w,
                    canvasHeight = h
                )
                val geomOut = com.example.engine.CameraBoxEngine.computeBoxGeometry(zoomedOut, w, h)
                assertEquals(
                    "Zoom-out aspect lock broken on $ratioLabel handle=$handle",
                    expectedAspect,
                    geomOut.aspectRatio,
                    0.002f
                )
                assertTrue(zoomedOut.zoom in com.example.engine.CameraBoxEngine.MIN_ZOOM..com.example.engine.CameraBoxEngine.MAX_ZOOM)
            }
        }

        // Pan drag + Dutch tilt rotation + cardinal 0-degree snap + handle hit-testing
        val stageW = 1280f
        val stageH = 720f
        val panned = com.example.engine.CameraBoxEngine.applyPanDrag(
            baseBox = baseBox,
            totalDeltaPxX = 128f * com.example.engine.CameraBoxEngine.DIRECTOR_STAGE_ZOOM,
            totalDeltaPxY = -72f * com.example.engine.CameraBoxEngine.DIRECTOR_STAGE_ZOOM,
            canvasWidth = stageW,
            canvasHeight = stageH
        )
        assertEquals(0.6f, panned.centerX, 0.005f)
        assertEquals(0.4f, panned.centerY, 0.005f)

        val geomBase = com.example.engine.CameraBoxEngine.computeBoxGeometry(baseBox, stageW, stageH)
        // Pointer straight above center -> snaps cleanly to 0 degrees
        val snappedRot = com.example.engine.CameraBoxEngine.applyRotationDrag(
            box = baseBox,
            pointerPxX = geomBase.centerX + 2f,
            pointerPxY = geomBase.centerY - 220f,
            canvasWidth = stageW,
            canvasHeight = stageH
        )
        assertEquals(0f, snappedRot.rotation, 0.01f)

        // Pointer to the right of center -> ~90 degrees clockwise
        val tiltedRot = com.example.engine.CameraBoxEngine.applyRotationDrag(
            box = baseBox,
            pointerPxX = geomBase.centerX + 220f,
            pointerPxY = geomBase.centerY,
            canvasWidth = stageW,
            canvasHeight = stageH
        )
        assertEquals(90f, tiltedRot.rotation, 1.0f)

        // Hit-testing rotation stem & corner on active box
        val rotHit = com.example.engine.CameraBoxEngine.hitTestBoxes(
            boxes = listOf(baseBox),
            selectedBoxId = baseBox.id,
            touchPxX = geomBase.rotateHandle.first,
            touchPxY = geomBase.rotateHandle.second,
            canvasWidth = stageW,
            canvasHeight = stageH
        )
        assertEquals(com.example.engine.CameraBoxHandle.ROTATE_STEM, rotHit?.second)
    }

    @Test
    fun `camera boxes A to B to C interpolate smoothly and execute exact Hold instant cuts`() {
        val boxA = CameraBoxClip(
            id = "box_a",
            label = "A",
            startFrame = 0,
            endFrame = 24,
            centerX = 0.25f,
            centerY = 0.50f,
            zoom = 1.0f,
            rotation = -10f,
            easing = EasingType.LINEAR
        )
        val boxB = CameraBoxClip(
            id = "box_b",
            label = "B",
            startFrame = 24,
            endFrame = 48,
            centerX = 0.75f,
            centerY = 0.30f,
            zoom = 2.0f,
            rotation = 10f,
            easing = EasingType.HOLD // Instant cut from B -> C at frame 48
        )
        val boxC = CameraBoxClip(
            id = "box_c",
            label = "C",
            startFrame = 48,
            endFrame = 72,
            centerX = 0.50f,
            centerY = 0.60f,
            zoom = 1.4f,
            rotation = 0f,
            easing = EasingType.CUSTOM_CUBIC,
            easingP1X = 0.22f,
            easingP1Y = 1.0f,
            easingP2X = 0.36f,
            easingP2Y = 1.0f
        )
        val scene = AnimationScene(
            id = "scene_cam_abc",
            durationFrames = 96,
            cameraLayerEnabled = true,
            cameraBoxes = listOf(boxA, boxB, boxC)
        )

        // Start of A
        val f0 = KeyframeEvaluator.evaluateCamera(scene, 0)
        assertEquals(0.25f, f0.x, 0.001f)
        assertEquals(1.0f, f0.zoom, 0.001f)
        assertEquals(-10f, f0.rotation, 0.001f)

        // Exact midpoint of A -> B (LINEAR at frame 12)
        val f12 = KeyframeEvaluator.evaluateCamera(scene, 12)
        assertEquals(0.50f, f12.x, 0.001f)
        assertEquals(0.40f, f12.y, 0.001f)
        assertEquals(1.50f, f12.zoom, 0.001f)
        assertEquals(0f, f12.rotation, 0.001f)

        // Inside B (HOLD): must stay locked strictly on B up through frame 47 (zero drift)
        val f36 = KeyframeEvaluator.evaluateCamera(scene, 36)
        val f47 = KeyframeEvaluator.evaluateCamera(scene, 47)
        assertEquals(0.75f, f36.x, 0.0001f)
        assertEquals(2.0f, f36.zoom, 0.0001f)
        assertEquals(0.75f, f47.x, 0.0001f)
        assertEquals(2.0f, f47.zoom, 0.0001f)
        assertEquals(10f, f47.rotation, 0.0001f)

        // Exact frame 48: instant cut to C!
        val f48 = KeyframeEvaluator.evaluateCamera(scene, 48)
        assertEquals(0.50f, f48.x, 0.0001f)
        assertEquals(0.60f, f48.y, 0.0001f)
        assertEquals(1.40f, f48.zoom, 0.0001f)
        assertEquals(0f, f48.rotation, 0.0001f)

        // Verify disabled camera layer returns neutral identity camera
        val disabledScene = scene.copy(cameraLayerEnabled = false)
        val fDisabled = KeyframeEvaluator.evaluateCamera(disabledScene, 12)
        assertEquals(0.5f, fDisabled.x, 0.0001f)
        assertEquals(1.0f, fDisabled.zoom, 0.0001f)
    }

    @Test
    fun `camera box CRUD supports auto-labeling A B C D, trim, split, duplicate, delete, and lock guard`() {
        var project = ProjectDocument(
            name = "MultiCam CRUD",
            scenes = listOf(AnimationScene(durationFrames = 144))
        )

        // Add A, B, C
        val (p1, idA) = ProjectMutator.addCameraBox(project, atFrame = 0)
        assertNotNull(idA)
        val (p2, idB) = ProjectMutator.addCameraBox(p1, atFrame = 48)
        assertNotNull(idB)
        val (p3, idC) = ProjectMutator.addCameraBox(p2, atFrame = 96)
        assertNotNull(idC)
        project = p3
        assertEquals(listOf("A", "B", "C"), project.activeScene.cameraBoxes.map { it.label })

        // Split A in the middle -> produces 4 chronological boxes A, B, C, D
        val boxA = project.activeScene.cameraBoxes.first { it.id == idA }
        val midFrame = (boxA.startFrame + boxA.endFrame) / 2
        val (pSplit, splitRightId) = ProjectMutator.splitCameraBoxAtFrame(project, idA!!, midFrame)
        assertNotNull(splitRightId)
        assertEquals(listOf("A", "B", "C", "D"), pSplit.activeScene.cameraBoxes.map { it.label })

        // Cycle easing on A -> switches through EASE_IN_OUT -> LINEAR -> HOLD -> CUSTOM_CUBIC
        val pLinear = ProjectMutator.cycleCameraBoxEasing(pSplit, idA)
        assertEquals(EasingType.LINEAR, pLinear.activeScene.cameraBoxes.first { it.id == idA }.easing)
        val pHold = ProjectMutator.cycleCameraBoxEasing(pLinear, idA)
        assertEquals(EasingType.HOLD, pHold.activeScene.cameraBoxes.first { it.id == idA }.easing)

        // Duplicate C -> adds 5th box E
        val (pDup, dupId) = ProjectMutator.duplicateCameraBox(pHold, idC!!)
        assertNotNull(dupId)
        assertEquals(listOf("A", "B", "C", "D", "E"), pDup.activeScene.cameraBoxes.map { it.label })

        // Trim B to new bounds
        val pTrim = ProjectMutator.trimOrMoveCameraBox(pDup, splitRightId!!, newStartFrame = 20, newEndFrame = 44)
        val trimmed = pTrim.activeScene.cameraBoxes.first { it.id == splitRightId }
        assertEquals(20, trimmed.startFrame)
        assertEquals(44, trimmed.endFrame)

        // Delete box -> remaining boxes cleanly relabel A, B, C, D
        val pDel = ProjectMutator.deleteCameraBox(pTrim, dupId!!)
        assertEquals(listOf("A", "B", "C", "D"), pDel.activeScene.cameraBoxes.map { it.label })

        // Locked camera layer blocks mutations
        val lockedProject = ProjectMutator.toggleCameraLayerLocked(pDel)
        assertTrue(lockedProject.activeScene.cameraLayerLocked)
        val (afterLockedAdd, lockedNewId) = ProjectMutator.addCameraBox(lockedProject, atFrame = 10)
        assertNull(lockedNewId)
        assertEquals(lockedProject, afterLockedAdd)
        assertEquals(lockedProject, ProjectMutator.deleteCameraBox(lockedProject, idA))
    }

    @Test
    fun `camera boxes sync cleanly with fcblueprint, fcscript cameraBoxes and cameraShots, and Moshi persistence`() {
        val baseScene = AnimationScene(
            id = "scene_cam_sync",
            name = "Camera Sync",
            durationFrames = 96,
            cameraLayerEnabled = true,
            cameraBoxes = listOf(
                CameraBoxClip(
                    id = "box_a",
                    label = "A",
                    startFrame = 0,
                    endFrame = 48,
                    centerX = 0.35f,
                    centerY = 0.50f,
                    zoom = 1.1f,
                    rotation = 0f,
                    easing = EasingType.EASE_IN_OUT
                ),
                CameraBoxClip(
                    id = "box_b",
                    label = "B",
                    startFrame = 48,
                    endFrame = 96,
                    centerX = 0.65f,
                    centerY = 0.42f,
                    zoom = 1.85f,
                    rotation = -6f,
                    easing = EasingType.HOLD
                )
            )
        )
        val baseProject = ProjectDocument(name = "CamSyncProject", fps = 24, scenes = listOf(baseScene))

        // 1. Export .fcblueprint.json and verify cameraBoxes payload
        val blueprint = com.example.engine.AIBlueprintExporter.buildBlueprint(
            project = baseProject,
            characters = BuiltInCatalog.builtInCharacters,
            mediaAssets = emptyList()
        )
        assertEquals(2, blueprint.projectAssets.cameraBoxes.size)
        assertEquals("A", blueprint.projectAssets.cameraBoxes[0].id)
        assertEquals(0.0, blueprint.projectAssets.cameraBoxes[0].startSec, 0.01)
        assertEquals(2.0, blueprint.projectAssets.cameraBoxes[0].endSec, 0.01)
        assertEquals("B", blueprint.projectAssets.cameraBoxes[1].id)
        assertEquals(1.85f, blueprint.projectAssets.cameraBoxes[1].zoom, 0.01f)
        assertEquals("HOLD", blueprint.projectAssets.cameraBoxes[1].easing)

        // 2. Import .fcscript.json with explicit cameraBoxes[] AND verify dry-run + compiled boxes
        val explicitScript = """
            {
              "schemaVersion": 3,
              "projectName": "MultiCam Film",
              "fps": 24,
              "scenes": [
                {
                  "name": "Act 1",
                  "durationSec": 4.0,
                  "cameraBoxes": [
                    { "id": "A", "startSec": 0.0, "endSec": 1.5, "centerX": 0.3, "centerY": 0.5, "zoom": 1.0, "rotation": 0.0, "easing": "EASE_IN_OUT" },
                    { "id": "B", "startSec": 1.5, "endSec": 3.0, "centerX": 0.7, "centerY": 0.4, "zoom": 1.9, "rotation": 8.0, "easing": "HOLD" },
                    { "id": "C", "startSec": 3.0, "endSec": 4.0, "centerX": 0.5, "centerY": 0.5, "zoom": 1.25, "rotation": -4.0, "easing": "LINEAR" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val (_, dryRun) = com.example.engine.DirectorScriptReceiver.validateAndDryRun(
            scriptJson = explicitScript,
            existingCharacters = BuiltInCatalog.builtInCharacters,
            existingMediaAssets = emptyList()
        )
        assertTrue(dryRun.isValid)
        assertEquals(3, dryRun.cameraBoxCount)

        val compiledExplicit = com.example.engine.DirectorScriptReceiver.compileIntoProject(
            context = null,
            scriptJson = explicitScript,
            currentProject = baseProject,
            existingCharacters = BuiltInCatalog.builtInCharacters,
            existingMediaAssets = emptyList(),
            importMode = com.example.engine.DirectorScriptImportMode.REPLACE_TIMELINE
        ).getOrThrow()

        val importedBoxes = compiledExplicit.project.activeScene.cameraBoxes
        assertEquals(listOf("A", "B", "C"), importedBoxes.map { it.label })
        assertEquals(0, importedBoxes[0].startFrame)
        assertEquals(36, importedBoxes[1].startFrame)
        assertEquals(EasingType.HOLD, importedBoxes[1].easing)
        assertEquals(72, importedBoxes[2].startFrame)
        assertEquals(1.9f, importedBoxes[1].zoom, 0.01f)

        // 3. Import .fcscript.json using cameraShots[] -> must auto-populate editable visual A/B boxes!
        val shotsScript = """
            {
              "schemaVersion": 3,
              "projectName": "Shots To Boxes",
              "fps": 24,
              "scenes": [
                {
                  "name": "Dialog",
                  "durationSec": 4.0,
                  "cameraShots": [
                    { "tSec": 0.0, "shotType": "WIDE_ESTABLISHING", "x": 0.5, "y": 0.5, "zoom": 1.0, "easing": "EASE_IN_OUT" },
                    { "tSec": 2.0, "shotType": "CLOSE_UP_SPEAKER", "x": 0.62, "y": 0.38, "zoom": 1.8, "easing": "HOLD" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val compiledShots = com.example.engine.DirectorScriptReceiver.compileIntoProject(
            context = null,
            scriptJson = shotsScript,
            currentProject = baseProject,
            existingCharacters = BuiltInCatalog.builtInCharacters,
            existingMediaAssets = emptyList(),
            importMode = com.example.engine.DirectorScriptImportMode.REPLACE_TIMELINE
        ).getOrThrow()

        val shotBoxes = compiledShots.project.activeScene.cameraBoxes
        assertEquals(listOf("A", "B"), shotBoxes.map { it.label })
        assertEquals(0, shotBoxes[0].startFrame)
        assertEquals(48, shotBoxes[0].endFrame)
        assertEquals(48, shotBoxes[1].startFrame)
        assertEquals(1.8f, shotBoxes[1].zoom, 0.01f)

        // 4. Moshi JSON round-trip persistence
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ProjectDocument::class.java)
        val restored = adapter.fromJson(adapter.toJson(compiledExplicit.project))
        assertNotNull(restored)
        assertEquals(3, restored!!.activeScene.cameraBoxes.size)
        assertEquals(listOf("A", "B", "C"), restored.activeScene.cameraBoxes.map { it.label })
        assertEquals(EasingType.HOLD, restored.activeScene.cameraBoxes[1].easing)
    }

    @Test
    fun `json auto-sanitizer cleans markdown fences, leading prose, comments, trailing commas, and builds error fix prompt`() {
        val messyLlmResponse = """
            Sure! Here is your cinematic FrameCraft director script:
            ```json
            {
              // Single-line comment that would normally break JSON
              "format": "framecraft.director_script",
              "schemaVersion": 3,
              "projectName": "Monsoon Drama",
              /* Multi-line block comment
                 describing inline SVG with http:// URL that MUST NOT be stripped */
              "inlineSvgAssets": [
                {
                  "id": "svg_lamp",
                  "type": "PROP",
                  "viewBox": "0 0 64 64",
                  "svgXml": "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'><circle cx='32' cy='32' r='16' fill='#FDE047'/></svg>",
                },
              ],
              "scenes": [
                {
                  "name": "Rainy Street",
                  "durationSec": 5.0,
                  "colourGrading": "NIGHT_MOONLIGHT",
                  "weatherLayers": [
                    { "style": "RAIN", "density": 0.75, },
                  ],
                  "cameraBoxes": [
                    { "id": "A", "startSec": 0.0, "endSec": 5.0, "centerX": 0.5, "centerY": 0.5, "zoom": 1.15, "rotation": 0.0, "easing": "EASE_IN_OUT", },
                  ],
                },
              ],
            }
            ```
            Hope you love this animation! Let me know if you want changes.
        """.trimIndent()

        val sanitized = com.example.engine.DirectorScriptJsonSanitizer.sanitizeLlmJson(messyLlmResponse)
        assertTrue(sanitized.extractedFromFence)
        assertTrue(sanitized.fixesApplied.isNotEmpty())
        // Must preserve http:// inside string literals
        assertTrue(sanitized.sanitizedJson.contains("http://www.w3.org/2000/svg"))

        val (dto, report) = com.example.engine.DirectorScriptReceiver.validateAndDryRun(
            scriptJson = messyLlmResponse,
            existingCharacters = BuiltInCatalog.builtInCharacters,
            existingMediaAssets = emptyList()
        )
        assertNotNull(dto)
        assertTrue("Expected messy LLM JSON to validate cleanly, errors=${report.errors}", report.isValid)
        assertEquals("Monsoon Drama", report.scriptTitle)
        assertEquals(1, report.sceneCount)
        assertEquals(5.0f, report.totalDurationSec, 0.05f)
        assertEquals(1, report.inlineSvgCount)
        assertEquals(1, report.weatherLayerCount)
        assertEquals(1, report.cameraBoxCount)
        assertTrue(report.sanitizationFixes.isNotEmpty())

        // Verify broken JSON produces actionable errorHint + errorSnippet + 1-tap Fix Prompt
        val brokenJson = """
            Here is your script:
            {
              "format": "framecraft.director_script",
              "scenes": [
                { "name": "Broken", "durationSec": "NOT_A_NUMBER" }
              ]
            }
        """.trimIndent()
        val (_, failReport) = com.example.engine.DirectorScriptReceiver.validateAndDryRun(
            scriptJson = brokenJson,
            existingCharacters = BuiltInCatalog.builtInCharacters,
            existingMediaAssets = emptyList()
        )
        assertFalse(failReport.isValid)
        assertTrue(failReport.errors.isNotEmpty())
        assertFalse(failReport.errorHint.isNullOrBlank())
        assertFalse(failReport.errorSnippet.isNullOrBlank())

        val fixPrompt = com.example.engine.DirectorScriptReceiver.buildErrorFixPrompt(brokenJson, failReport)
        assertTrue(fixPrompt.contains("REPAIR REQUEST"))
        assertTrue(fixPrompt.contains("EXACT ERROR"))
        assertTrue(fixPrompt.contains("durationSec"))
    }

    @Test
    fun `blueprint compact mode shrinks tokens, Include Current Timeline State snapshots active scene, and Complete Prompt bundles instructions`() {
        val heroId = BuiltInCatalog.builtInCharacters.first().id
        val scene = AnimationScene(
            id = "scene_refine",
            name = "Village Sunset",
            durationFrames = 120,
            backgroundColorHex = "#1E293B",
            vfxConfig = SceneVfxConfig(lightingMood = SceneLightingMood.GOLDEN_HOUR, particleType = SceneParticleType.FIREFLIES),
            layers = listOf(
                AnimationLayer(id = "l_char", name = "Hero", type = LayerType.CHARACTER, orderIndex = 0)
            ),
            clips = listOf(
                TimelineClip(
                    id = "clip_hero_1",
                    layerId = "l_char",
                    name = "Aarav",
                    startFrame = 0,
                    durationFrames = 96,
                    characterId = heroId,
                    animationPresetType = AnimationPresetType.TALKING,
                    keyframes = listOf(
                        TransformKeyframe(frame = 0, x = 0.35f, y = 0.82f),
                        TransformKeyframe(frame = 48, x = 0.55f, y = 0.82f, easing = EasingType.EASE_IN_OUT)
                    )
                )
            ),
            dialogueOverlays = listOf(
                DialogueOverlayBeat(
                    id = "dlg_1",
                    speakerName = "Aarav",
                    speakerCharacterId = heroId,
                    text = "ନମସ୍କାର ସାଙ୍ଗ!",
                    language = "or",
                    startFrame = 12,
                    endFrame = 72,
                    showSpeechBubble = false,
                    showSubtitle = true
                )
            ),
            cameraKeyframes = listOf(
                CameraKeyframe(frame = 0, x = 0.5f, y = 0.5f, zoom = 1.0f),
                CameraKeyframe(frame = 48, x = 0.45f, y = 0.48f, zoom = 1.6f, easing = EasingType.EASE_IN_OUT)
            )
        )
        val project = ProjectDocument(name = "Refine Test", fps = 24, scenes = listOf(scene))

        // 1. Default (Include Current Timeline State = OFF)
        val fullDefaultBp = com.example.engine.AIBlueprintExporter.buildBlueprint(
            project = project,
            characters = BuiltInCatalog.builtInCharacters,
            mediaAssets = emptyList()
        )
        assertNull(fullDefaultBp.currentTimelineState)

        // 2. Include Current Timeline State = ON
        val refineOptions = com.example.engine.AIBlueprintExporter.BlueprintExportOptions(
            mode = com.example.engine.AIBlueprintExporter.BlueprintExportMode.FULL,
            includeCurrentTimelineState = true
        )
        val refineBp = com.example.engine.AIBlueprintExporter.buildBlueprint(
            project = project,
            characters = BuiltInCatalog.builtInCharacters,
            mediaAssets = emptyList(),
            options = refineOptions
        )
        val timelineState = refineBp.currentTimelineState
        assertNotNull(timelineState)
        assertEquals("Village Sunset", timelineState!!.sceneName)
        assertEquals(5.0, timelineState.durationSec, 0.01)
        assertEquals(1, timelineState.clips.size)
        assertEquals("clip_hero_1", timelineState.clips[0].id)
        assertEquals(2, timelineState.clips[0].keyframes.size)
        assertEquals(1, timelineState.dialogueTimings.size)
        assertEquals("or", timelineState.dialogueTimings[0].language)
        assertEquals(2, timelineState.cameraKeyframes.size)

        // 3. Compact mode produces significantly fewer tokens than Full mode
        val fullJson = com.example.engine.AIBlueprintExporter.exportToJson(
            project = project,
            characters = BuiltInCatalog.builtInCharacters,
            mediaAssets = emptyList(),
            prettyPrint = true,
            options = com.example.engine.AIBlueprintExporter.BlueprintExportOptions(
                mode = com.example.engine.AIBlueprintExporter.BlueprintExportMode.FULL,
                includeCurrentTimelineState = true
            )
        )
        val compactJson = com.example.engine.AIBlueprintExporter.exportToJson(
            project = project,
            characters = BuiltInCatalog.builtInCharacters,
            mediaAssets = emptyList(),
            prettyPrint = false,
            options = com.example.engine.AIBlueprintExporter.BlueprintExportOptions(
                mode = com.example.engine.AIBlueprintExporter.BlueprintExportMode.COMPACT,
                includeCurrentTimelineState = true
            )
        )
        val fullTokens = com.example.engine.AIBlueprintExporter.estimateTokenCount(fullJson)
        val compactTokens = com.example.engine.AIBlueprintExporter.estimateTokenCount(compactJson)
        assertTrue("Expected compact ($compactTokens) < full ($fullTokens)", compactTokens < fullTokens)
        assertTrue(compactJson.contains("\"currentTimelineState\""))
        assertTrue(com.example.engine.AIBlueprintExporter.formatTokenEstimate(compactTokens).contains("tokens"))

        // 4. Complete Ready-to-Paste AI Prompt bundles rules + blueprint + Odia/Hindi/English story
        val story = "ଆରଭ ଓ ପ୍ରିୟା ସନ୍ଧ୍ୟାରେ ଗପସପ କରୁଛନ୍ତି — add warm golden hour close-up!"
        val completePrompt = com.example.engine.AIBlueprintExporter.buildCompleteAiPrompt(
            blueprintJson = compactJson,
            storyInstructions = story,
            options = com.example.engine.AIBlueprintExporter.BlueprintExportOptions(
                mode = com.example.engine.AIBlueprintExporter.BlueprintExportMode.COMPACT,
                includeCurrentTimelineState = true
            )
        )
        assertTrue(completePrompt.contains("MASTER FILM DIRECTOR RULES"))
        assertTrue(completePrompt.contains("REFINE EXISTING TIMELINE"))
        assertTrue(completePrompt.contains(compactJson.take(120)))
        assertTrue(completePrompt.contains(story))
    }

    @Test
    fun `selective track import merges only checked categories and preserves unchecked tracks`() {
        val heroId = BuiltInCatalog.builtInCharacters.first().id
        val existingScene = AnimationScene(
            id = "existing_s1",
            name = "Original Scene",
            durationFrames = 96,
            backgroundColorHex = "#112233",
            vfxConfig = SceneVfxConfig(lightingMood = SceneLightingMood.NONE, particleType = SceneParticleType.NONE),
            weatherLayers = emptyList(),
            cameraBoxes = listOf(
                CameraBoxClip(id = "old_cam", label = "A", startFrame = 0, endFrame = 96, centerX = 0.5f, centerY = 0.5f, zoom = 1.0f)
            ),
            dialogueOverlays = listOf(
                DialogueOverlayBeat(
                    id = "orig_dlg",
                    speakerName = "Orig",
                    text = "Keep original subtitle",
                    language = "en",
                    startFrame = 0,
                    endFrame = 48,
                    showSubtitle = true
                )
            ),
            layers = listOf(
                AnimationLayer(id = "orig_char_layer", name = "Orig Hero", type = LayerType.CHARACTER, orderIndex = 0),
                AnimationLayer(id = "orig_audio_layer", name = "Orig BGM", type = LayerType.AUDIO, orderIndex = 1)
            ),
            clips = listOf(
                TimelineClip(id = "orig_hero_clip", layerId = "orig_char_layer", name = "Original Hero", characterId = heroId),
                TimelineClip(id = "orig_audio_clip", layerId = "orig_audio_layer", name = "Original BGM", audioPath = "/tmp/orig.wav")
            )
        )
        val existingProject = ProjectDocument(
            name = "Selective Base",
            fps = 24,
            audioDucking = AudioDuckingConfig(duckingEnabled = true, duckTargetVolume = 0.22f),
            scenes = listOf(existingScene)
        )

        val incomingScript = """
            {
              "format": "framecraft.director_script",
              "schemaVersion": 3,
              "audioDucking": { "duckingEnabled": false, "duckTargetVolume": 0.75 },
              "scenes": [
                {
                  "name": "Upgraded Scene",
                  "durationSec": 4.0,
                  "backgroundColorHex": "#FF00AA",
                  "colourGrading": "GOLDEN_HOUR",
                  "weatherLayers": [
                    { "style": "RAIN", "density": 0.8 }
                  ],
                  "cameraBoxes": [
                    { "id": "A", "startSec": 0.0, "endSec": 2.0, "centerX": 0.3, "centerY": 0.5, "zoom": 1.2, "easing": "EASE_IN_OUT" },
                    { "id": "B", "startSec": 2.0, "endSec": 4.0, "centerX": 0.7, "centerY": 0.4, "zoom": 2.0, "easing": "HOLD" }
                  ],
                  "dialogueOverlays": [
                    { "speakerName": "New", "text": "Incoming subtitle should be skipped", "language": "en", "startSec": 0.0, "endSec": 2.0, "showSubtitle": true }
                  ],
                  "clips": [
                    { "id": "new_char_clip", "name": "Incoming Char", "characterId": "$heroId", "startSec": 0.0, "durationSec": 4.0, "animationPreset": "WALKING" }
                  ]
                }
              ]
            }
        """.trimIndent()

        // Import ONLY Camera Shots + VFX/Weather/Grading!
        val cameraAndVfxOnly = com.example.engine.DirectorScriptTrackSelection(
            charactersAndActing = false,
            cameraShots = true,
            backgroundsAndParallax = false,
            vfxWeatherAndGrading = true,
            audioAndDucking = false,
            subtitlesAndBubbles = false
        )

        val compiled = com.example.engine.DirectorScriptReceiver.compileIntoProject(
            context = null,
            scriptJson = incomingScript,
            currentProject = existingProject,
            existingCharacters = BuiltInCatalog.builtInCharacters,
            existingMediaAssets = emptyList(),
            importMode = com.example.engine.DirectorScriptImportMode.REPLACE_TIMELINE,
            trackSelection = cameraAndVfxOnly
        ).getOrThrow()

        val resultScene = compiled.project.activeScene
        // Checked categories MUST update:
        assertEquals(2, resultScene.cameraBoxes.size)
        assertEquals(2.0f, resultScene.cameraBoxes[1].zoom, 0.01f)
        assertEquals(SceneLightingMood.GOLDEN_HOUR, resultScene.vfxConfig.lightingMood)
        assertEquals(1, resultScene.weatherLayers.size)
        assertEquals(WeatherParticleStyle.RAIN, resultScene.weatherLayers[0].style)

        // Unchecked categories MUST be preserved untouched:
        assertEquals("#112233", resultScene.backgroundColorHex)
        assertTrue(resultScene.clips.any { it.id == "orig_hero_clip" })
        assertFalse(resultScene.clips.any { it.id == "new_char_clip" })
        assertTrue(resultScene.clips.any { it.id == "orig_audio_clip" })
        assertEquals(0.22f, compiled.project.audioDucking.duckTargetVolume, 0.001f)
        assertTrue(compiled.project.audioDucking.duckingEnabled)
        assertEquals(1, resultScene.dialogueOverlays.size)
        assertEquals("Keep original subtitle", resultScene.dialogueOverlays[0].text)

        // Zero checked tracks must cleanly fail
        val emptySelection = com.example.engine.DirectorScriptTrackSelection(
            charactersAndActing = false,
            cameraShots = false,
            backgroundsAndParallax = false,
            vfxWeatherAndGrading = false,
            audioAndDucking = false,
            subtitlesAndBubbles = false
        )
        val emptyResult = com.example.engine.DirectorScriptReceiver.compileIntoProject(
            context = null,
            scriptJson = incomingScript,
            currentProject = existingProject,
            existingCharacters = BuiltInCatalog.builtInCharacters,
            existingMediaAssets = emptyList(),
            importMode = com.example.engine.DirectorScriptImportMode.REPLACE_TIMELINE,
            trackSelection = emptySelection
        )
        assertTrue(emptyResult.isFailure)
    }

    @Test
    fun `offline director script history caps at 10 entries, deduplicates, and persists to local json`() {
        val tempFile = java.io.File.createTempFile("director_script_history_test_", ".json")
        tempFile.deleteOnExit()
        try {
            val storage = com.example.data.DirectorScriptHistoryStorage(tempFile)
            assertTrue(storage.loadHistoryBlocking().isEmpty())

            // Record 12 distinct scripts -> strictly capped at 10 newest
            for (i in 1..12) {
                storage.recordAppliedScriptBlocking(
                    title = "Draft #$i",
                    scriptJson = """{"format":"framecraft.director_script","projectName":"Draft #$i","scenes":[{"name":"S$i","durationSec":3.0}]}""",
                    sceneCount = i,
                    totalDurationSec = i * 3.0f,
                    clipCount = i * 2,
                    cameraShotCount = i,
                    appliedAtMs = 1_700_000_000_000L + i * 1000L
                )
            }

            // Reload via a brand new storage instance to verify disk persistence
            val reloaded = com.example.data.DirectorScriptHistoryStorage(tempFile).loadHistoryBlocking()
            assertEquals(10, reloaded.size)
            assertEquals("Draft #12", reloaded.first().title)
            assertEquals("Draft #3", reloaded.last().title)

            // Re-applying identical JSON of Draft #5 deduplicates and bumps it to top
            val dupJson = reloaded.first { it.title == "Draft #5" }.scriptJson
            val afterDedup = storage.recordAppliedScriptBlocking(
                title = "Draft #5 Re-Run",
                scriptJson = dupJson,
                sceneCount = 5,
                totalDurationSec = 15.0f,
                appliedAtMs = 1_700_000_999_000L
            )
            assertEquals(10, afterDedup.size)
            assertEquals("Draft #5 Re-Run", afterDedup.first().title)
            assertEquals(1, afterDedup.count { it.scriptJson.trim() == dupJson.trim() })

            // Delete 1 entry & verify persisted count = 9
            val afterDelete = storage.deleteEntryBlocking(afterDedup.first().id)
            assertEquals(9, afterDelete.size)
            assertEquals(9, com.example.data.DirectorScriptHistoryStorage(tempFile).loadHistoryBlocking().size)

            // Clear all
            assertTrue(storage.clearAllBlocking().isEmpty())
            assertTrue(com.example.data.DirectorScriptHistoryStorage(tempFile).loadHistoryBlocking().isEmpty())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `universal NLE split preserves character keyframes expressions and audio trim offsets`() {
        val charLayer = AnimationLayer(id = "layer_char", name = "Hero", type = LayerType.CHARACTER, orderIndex = 1)
        val audioLayer = AnimationLayer(id = "layer_audio", name = "BGM", type = LayerType.AUDIO, orderIndex = 0)
        val lockedLayer = AnimationLayer(id = "layer_locked", name = "Locked", type = LayerType.TEXT, isLocked = true, orderIndex = 2)

        val charClip = TimelineClip(
            id = "clip_char",
            layerId = charLayer.id,
            name = "Hero Walk",
            startFrame = 10,
            durationFrames = 60,
            characterId = "char_1",
            keyframes = listOf(
                TransformKeyframe(frame = 0, x = 0.2f, y = 0.5f, expressionOverride = FacialExpression.HAPPY),
                TransformKeyframe(frame = 30, x = 0.5f, y = 0.5f, expressionOverride = FacialExpression.SAD),
                TransformKeyframe(frame = 50, x = 0.8f, y = 0.5f, expressionOverride = FacialExpression.ANGRY)
            ),
            simultaneousTracks = listOf(
                SimultaneousTrackClip(
                    id = "sim_1",
                    trackType = SimultaneousTrackType.HAND_GESTURE,
                    startLocalFrame = 10,
                    durationFrames = 40,
                    armGesturePreset = ArmGestureTrackPreset.WAVE
                )
            )
        )
        val audioClip = TimelineClip(
            id = "clip_audio",
            layerId = audioLayer.id,
            name = "Voiceover",
            startFrame = 0,
            durationFrames = 48, // 2.0s @ 24fps
            audioPath = "/tmp/voice.wav",
            audioTrimStartMs = 1000L,
            audioTrimEndMs = 3000L
        )
        val lockedClip = TimelineClip(
            id = "clip_locked",
            layerId = lockedLayer.id,
            name = "Locked Title",
            startFrame = 0,
            durationFrames = 40,
            textContent = "Keep Me"
        )

        val project = ProjectDocument(
            name = "NLE Split Test",
            fps = 24,
            scenes = listOf(
                AnimationScene(
                    name = "Scene 1",
                    durationFrames = 120,
                    layers = listOf(audioLayer, charLayer, lockedLayer),
                    clips = listOf(audioClip, charClip, lockedClip)
                )
            )
        )

        // 1. Split Character clip at global frame 40 (local frame 30)
        val charSplit = com.example.engine.TimelineNleEngine.splitClipUniversal(project, charClip.id, globalFrame = 40)
        assertTrue(charSplit.changed)
        assertNotNull(charSplit.createdClipId)
        val charHalves = charSplit.project.activeScene.clips.filter { it.layerId == charLayer.id }.sortedBy { it.startFrame }
        assertEquals(2, charHalves.size)
        assertEquals(10, charHalves[0].startFrame)
        assertEquals(30, charHalves[0].durationFrames)
        assertEquals(40, charHalves[1].startFrame)
        assertEquals(30, charHalves[1].durationFrames)
        // Second half starts with evaluated keyframe at local 0 (x ~= 0.5) and shifted keyframe at local 20 (x = 0.8)
        assertEquals(0.5f, charHalves[1].keyframes.first().x, 0.02f)
        assertEquals(20, charHalves[1].keyframes.last().frame)
        assertEquals(0.8f, charHalves[1].keyframes.last().x, 0.01f)
        assertEquals(FacialExpression.ANGRY, charHalves[1].keyframes.last().expressionOverride)
        // Simultaneous track was cleanly split across both halves
        assertEquals(1, charHalves[0].simultaneousTracks.size)
        assertEquals(1, charHalves[1].simultaneousTracks.size)

        // 2. Split Audio clip at global frame 24 (1.0s into 2.0s clip starting at trimStart 1000ms)
        val audioSplit = com.example.engine.TimelineNleEngine.splitClipUniversal(charSplit.project, audioClip.id, globalFrame = 24)
        assertTrue(audioSplit.changed)
        val audioHalves = audioSplit.project.activeScene.clips.filter { it.layerId == audioLayer.id }.sortedBy { it.startFrame }
        assertEquals(2, audioHalves.size)
        assertEquals(1000L, audioHalves[0].audioTrimStartMs)
        assertEquals(2000L, audioHalves[0].audioTrimEndMs)
        assertEquals(2000L, audioHalves[1].audioTrimStartMs)
        assertEquals(3000L, audioHalves[1].audioTrimEndMs)

        // 3. Locked layer blocks split cleanly
        val lockedSplit = com.example.engine.TimelineNleEngine.splitClipUniversal(audioSplit.project, lockedClip.id, globalFrame = 20)
        assertFalse(lockedSplit.changed)
    }

    @Test
    fun `universal NLE duplicate ripple delete hold cut and trim to playhead anchor keyframes`() {
        val layer = AnimationLayer(id = "layer_main", name = "Text Track", type = LayerType.TEXT, orderIndex = 0)
        val c1 = TimelineClip(
            id = "c1",
            layerId = layer.id,
            name = "Title 1",
            startFrame = 0,
            durationFrames = 24,
            textContent = "Intro",
            keyframes = listOf(
                TransformKeyframe(frame = 0, x = 0.2f, easing = EasingType.LINEAR),
                TransformKeyframe(frame = 12, x = 0.5f, easing = EasingType.LINEAR),
                TransformKeyframe(frame = 20, x = 0.8f, easing = EasingType.LINEAR)
            )
        )
        val project = ProjectDocument(
            name = "NLE Ops Test",
            fps = 24,
            scenes = listOf(
                AnimationScene(name = "S1", durationFrames = 120, layers = listOf(layer), clips = listOf(c1))
            )
        )

        // 1. Duplicate onto SAME track right after endFrame (frame 24) because slot [24..48) is clear
        val dup1 = com.example.engine.TimelineNleEngine.duplicateClipUniversal(project, c1.id)
        assertTrue(dup1.changed)
        assertNull(dup1.createdLayerId)
        val c2 = dup1.project.activeScene.clips.first { it.id == dup1.createdClipId }
        assertEquals(layer.id, c2.layerId)
        assertEquals(24, c2.startFrame)
        assertEquals(24, c2.durationFrames)

        // 2. Duplicate c1 AGAIN when [24..48) is now occupied -> spawns adjacent lane cleanly!
        val dup2 = com.example.engine.TimelineNleEngine.duplicateClipUniversal(dup1.project, c1.id)
        assertTrue(dup2.changed)
        assertNotNull(dup2.createdLayerId)
        val c3 = dup2.project.activeScene.clips.first { it.id == dup2.createdClipId }
        assertEquals(dup2.createdLayerId, c3.layerId)
        assertEquals(24, c3.startFrame)

        // 3. Toggle Hold (Cut) on c1 at frame 12 -> switches to HOLD, toggling again returns to EASE_IN_OUT
        val holdOn = com.example.engine.TimelineNleEngine.toggleClipHoldCutAtFrame(dup1.project, c1.id, globalFrame = 12)
        assertTrue(holdOn.changed)
        val kfHold = holdOn.project.activeScene.clips.first { it.id == c1.id }.keyframes.first { it.frame == 12 }
        assertEquals(EasingType.HOLD, kfHold.easing)
        val holdOff = com.example.engine.TimelineNleEngine.toggleClipHoldCutAtFrame(holdOn.project, c1.id, globalFrame = 12)
        val kfSmooth = holdOff.project.activeScene.clips.first { it.id == c1.id }.keyframes.first { it.frame == 12 }
        assertEquals(EasingType.EASE_IN_OUT, kfSmooth.easing)

        // 4. Left-Trim to Playhead at frame 8 anchors world keyframes (frame 12 -> local 4, frame 20 -> local 12)
        val trimmed = com.example.engine.TimelineNleEngine.trimClipToPlayhead(dup1.project, c1.id, globalFrame = 8, trimStart = true)
        assertTrue(trimmed.changed)
        val c1Trimmed = trimmed.project.activeScene.clips.first { it.id == c1.id }
        assertEquals(8, c1Trimmed.startFrame)
        assertEquals(16, c1Trimmed.durationFrames)
        assertTrue(c1Trimmed.keyframes.any { it.frame == 0 })
        assertTrue(c1Trimmed.keyframes.any { it.frame == 4 && kotlin.math.abs(it.x - 0.5f) < 0.01f })
        assertTrue(c1Trimmed.keyframes.any { it.frame == 12 && kotlin.math.abs(it.x - 0.8f) < 0.01f })

        // 5. Ripple Delete c1 -> closes gap automatically so c2 shifts left from frame 24 to frame 0!
        val rippleDel = com.example.engine.TimelineNleEngine.deleteClipUniversal(dup1.project, c1.id, ripple = true)
        assertTrue(rippleDel.changed)
        assertEquals(1, rippleDel.project.activeScene.clips.size)
        val c2AfterRipple = rippleDel.project.activeScene.clips.first()
        assertEquals(c2.id, c2AfterRipple.id)
        assertEquals(0, c2AfterRipple.startFrame)
    }

    @Test
    fun `cross track drag and drop enforces compatibility resolves overlap and supports undo rollback`() {
        val charL1 = AnimationLayer(id = "char_1", name = "Hero 1", type = LayerType.CHARACTER, orderIndex = 0)
        val charL2 = AnimationLayer(id = "char_2", name = "Hero 2", type = LayerType.CHARACTER, orderIndex = 1)
        val audioL1 = AnimationLayer(id = "aud_1", name = "BGM 1", type = LayerType.AUDIO, orderIndex = 2)
        val audioL2 = AnimationLayer(id = "aud_2", name = "SFX 2", type = LayerType.AUDIO, orderIndex = 3)

        val heroA = TimelineClip(id = "hero_a", layerId = charL1.id, name = "Hero A", startFrame = 0, durationFrames = 20, characterId = "cA")
        val heroB = TimelineClip(id = "hero_b", layerId = charL2.id, name = "Hero B", startFrame = 10, durationFrames = 20, characterId = "cB")
        val bgm = TimelineClip(id = "bgm_1", layerId = audioL1.id, name = "Music", startFrame = 0, durationFrames = 40, audioPath = "/tmp/m.wav")

        val initialProject = ProjectDocument(
            name = "Cross-Track Test",
            scenes = listOf(
                AnimationScene(
                    name = "Scene 1",
                    durationFrames = 120,
                    layers = listOf(charL1, charL2, audioL1, audioL2),
                    clips = listOf(heroA, heroB, bgm)
                )
            )
        )

        // Undo stack simulation
        val undoStack = ArrayDeque<ProjectDocument>()
        undoStack.addLast(initialProject)

        // 1. Incompatible drop: Audio -> Character track is cleanly rejected
        val invalidDrop = com.example.engine.TimelineNleEngine.moveClipAcrossTracks(
            project = initialProject,
            clipId = bgm.id,
            targetLayerId = charL1.id,
            targetStartFrame = 15
        )
        assertFalse(invalidDrop.changed)
        assertTrue(invalidDrop.message.contains("Audio", ignoreCase = true))

        // 2. Compatible Audio -> Audio cross-track move succeeds
        val audioMove = com.example.engine.TimelineNleEngine.moveClipAcrossTracks(
            project = initialProject,
            clipId = bgm.id,
            targetLayerId = audioL2.id,
            targetStartFrame = 12
        )
        assertTrue(audioMove.changed)
        val movedBgm = audioMove.project.activeScene.clips.first { it.id == bgm.id }
        assertEquals(audioL2.id, movedBgm.layerId)
        assertEquals(12, movedBgm.startFrame)

        // 3. Smart overlap collision handling: Dropping Hero A (dur 20) onto charL2 at frame 15
        // collides with Hero B [10..30) -> snaps cleanly adjacent to frame 30 (no silent overlap!)
        val overlapMove = com.example.engine.TimelineNleEngine.moveClipAcrossTracks(
            project = audioMove.project,
            clipId = heroA.id,
            targetLayerId = charL2.id,
            targetStartFrame = 15
        )
        assertTrue(overlapMove.changed)
        val movedHeroA = overlapMove.project.activeScene.clips.first { it.id == heroA.id }
        assertEquals(charL2.id, movedHeroA.layerId)
        assertEquals(30, movedHeroA.startFrame)

        // 4. Drop onto "+ New Track" spawns a matching CHARACTER track and moves clip there
        val newTrackMove = com.example.engine.TimelineNleEngine.moveClipAcrossTracks(
            project = overlapMove.project,
            clipId = heroA.id,
            targetLayerId = null,
            targetStartFrame = 5,
            createNewTrack = true,
            insertNewTrackAtTop = true
        )
        assertTrue(newTrackMove.changed)
        assertNotNull(newTrackMove.createdLayerId)
        assertEquals(5, newTrackMove.project.activeScene.layers.size)
        val heroOnNew = newTrackMove.project.activeScene.clips.first { it.id == heroA.id }
        assertEquals(newTrackMove.createdLayerId, heroOnNew.layerId)
        assertEquals(5, heroOnNew.startFrame)

        // 5. Undo rollback restores original state untouched (immutability verification)
        val rolledBack = undoStack.removeLast()
        assertEquals(4, rolledBack.activeScene.layers.size)
        assertEquals(charL1.id, rolledBack.activeScene.clips.first { it.id == heroA.id }.layerId)
        assertEquals(audioL1.id, rolledBack.activeScene.clips.first { it.id == bgm.id }.layerId)
    }

    @Test
    fun `magnetic snapping engine snaps move and trim edges within 10dp tolerance to playhead clips and markers`() {
        val layer1 = AnimationLayer(id = "l1", name = "Hero", type = LayerType.CHARACTER, orderIndex = 0)
        val layer2 = AnimationLayer(id = "l2", name = "Title", type = LayerType.TEXT, orderIndex = 1)
        val moving = TimelineClip(id = "moving", layerId = layer1.id, name = "Hero", startFrame = 0, durationFrames = 20)
        val anchor = TimelineClip(id = "anchor", layerId = layer2.id, name = "Title", startFrame = 35, durationFrames = 15) // [35..50)
        val scene = AnimationScene(
            name = "Snap Scene",
            durationFrames = 120,
            layers = listOf(layer1, layer2),
            clips = listOf(moving, anchor)
        )

        val targets = com.example.engine.TimelineNleEngine.collectSnapTargets(
            scene = scene,
            playheadFrame = 22,
            majorMarkerStep = 12,
            excludeClipId = moving.id
        )
        // Assume frameWidthPx = 10px, snapTolerancePx = 15px (1.5 frames tolerance)
        // 1. MOVE start near Playhead (21 -> snaps to 22)
        val snapPlayhead = com.example.engine.TimelineNleEngine.computeMagneticSnap(
            candidateStartFrame = 21,
            candidateEndFrame = 41,
            mode = com.example.engine.SnapDragMode.MOVE,
            snapTargets = targets,
            frameWidthPx = 10f,
            snapTolerancePx = 15f
        )
        assertEquals(22, snapPlayhead.snappedStartFrame)
        assertEquals(42, snapPlayhead.snappedEndFrame)
        assertEquals(22, snapPlayhead.snappedGuideFrame)
        assertEquals(com.example.engine.SnapSource.PLAYHEAD, snapPlayhead.snapSource)

        // 2. MOVE end edge near other clip's end (candidate [29..49) -> end snaps to 50 -> [30..50))
        val snapClipEnd = com.example.engine.TimelineNleEngine.computeMagneticSnap(
            candidateStartFrame = 29,
            candidateEndFrame = 49,
            mode = com.example.engine.SnapDragMode.MOVE,
            snapTargets = targets,
            frameWidthPx = 10f,
            snapTolerancePx = 15f
        )
        assertEquals(30, snapClipEnd.snappedStartFrame)
        assertEquals(50, snapClipEnd.snappedEndFrame)
        assertEquals(50, snapClipEnd.snappedGuideFrame)
        assertEquals(com.example.engine.SnapSource.CLIP_END, snapClipEnd.snapSource)

        // 3. TRIM_LEFT near other clip's start (34 -> snaps to 35)
        val snapTrimLeft = com.example.engine.TimelineNleEngine.computeMagneticSnap(
            candidateStartFrame = 34,
            candidateEndFrame = 60,
            mode = com.example.engine.SnapDragMode.TRIM_LEFT,
            snapTargets = targets,
            frameWidthPx = 10f,
            snapTolerancePx = 15f
        )
        assertEquals(35, snapTrimLeft.snappedStartFrame)
        assertEquals(35, snapTrimLeft.snappedGuideFrame)
        assertEquals(com.example.engine.SnapSource.CLIP_START, snapTrimLeft.snapSource)

        // 4. TRIM_RIGHT near major ruler marker (47 -> snaps to 48)
        val snapTrimRight = com.example.engine.TimelineNleEngine.computeMagneticSnap(
            candidateStartFrame = 10,
            candidateEndFrame = 47,
            mode = com.example.engine.SnapDragMode.TRIM_RIGHT,
            snapTargets = targets,
            frameWidthPx = 10f,
            snapTolerancePx = 15f
        )
        assertEquals(48, snapTrimRight.snappedEndFrame)
        assertEquals(48, snapTrimRight.snappedGuideFrame)
        assertEquals(com.example.engine.SnapSource.RULER_MARKER, snapTrimRight.snapSource)

        // 5. Outside tolerance -> does not snap
        val noSnap = com.example.engine.TimelineNleEngine.computeMagneticSnap(
            candidateStartFrame = 16,
            candidateEndFrame = 31,
            mode = com.example.engine.SnapDragMode.MOVE,
            snapTargets = targets,
            frameWidthPx = 10f,
            snapTolerancePx = 10f
        )
        assertNull(noSnap.snappedGuideFrame)
        assertEquals(16, noSnap.snappedStartFrame)
    }

    @Test
    fun `Pro Tools 1 - Replace character and media swaps asset while preserving 100 percent of keyframes, IK pose, motionPath, FX, and sway`() {
        val char1 = BuiltInCatalog.builtInCharacters[0]
        val char2 = BuiltInCatalog.builtInCharacters[1]
        val origClip = TimelineClip(
            id = "c_rep_char",
            layerId = "l_char",
            name = "${char1.name} • Walk",
            startFrame = 12,
            durationFrames = 64,
            characterId = char1.id,
            orientation = CharacterOrientation.SIDE,
            animationPresetType = AnimationPresetType.WALK,
            facialExpression = FacialExpression.SAD,
            propSwayEnabled = true,
            propSwayStrength = 1.45f,
            tearsConfig = TearsFxConfig(TearsFxStyle.FLOWING, 0.85f, 1.2f),
            shadowConfig = ShadowConfig(enabled = true, mode = ShadowMode.GROUND_ELLIPSE, opacity = 0.5f),
            colorAdjust = ClipColorAdjust(brightness = 0.1f, contrast = 1.2f, saturation = 1.3f, warmth = 0.25f),
            maskType = ClipMaskType.ROUNDED_RECT,
            maskConfig = ClipMaskConfig(type = ClipMaskType.ROUNDED_RECT, width = 0.8f, height = 0.9f, invert = true),
            motionPathControlPoints = listOf(NormalizedPoint(0.2f, 0.7f), NormalizedPoint(0.8f, 0.5f)),
            keyframes = listOf(
                TransformKeyframe(frame = 0, x = 0.2f, y = 0.7f, poseOffsets = PoseKeyframeOffsets(headNodOffset = 14f, rightArmOffset = -28f)),
                TransformKeyframe(frame = 32, x = 0.75f, y = 0.65f, easing = EasingType.EASE_IN_OUT)
            )
        )
        val imgClip = TimelineClip(
            id = "c_rep_img",
            layerId = "l_img",
            name = "Old BG",
            startFrame = 0,
            durationFrames = 80,
            imagePath = "/old/path.png",
            bundledBgKey = "bg_old",
            inlineSvgAssetId = "svg_old",
            shadowConfig = ShadowConfig(enabled = true, mode = ShadowMode.DROP_SHADOW),
            colorAdjust = ClipColorAdjust(warmth = -0.35f),
            keyframes = listOf(TransformKeyframe(frame = 0, x = 0.5f, y = 0.5f, scaleX = 1.2f))
        )
        val doc = ProjectDocument(
            scenes = listOf(
                AnimationScene(
                    layers = listOf(
                        AnimationLayer(id = "l_img", name = "Img", type = LayerType.IMAGE, orderIndex = 0),
                        AnimationLayer(id = "l_char", name = "Char", type = LayerType.CHARACTER, orderIndex = 1)
                    ),
                    clips = listOf(imgClip, origClip)
                )
            )
        )

        val swappedCharDoc = ProjectMutator.replaceClipCharacter(doc, origClip.id, char2.id, char2.name)
        val swappedChar = swappedCharDoc.activeScene.clips.first { it.id == origClip.id }
        assertEquals(char2.id, swappedChar.characterId)
        assertTrue(swappedChar.name.startsWith(char2.name))
        assertEquals(origClip.startFrame, swappedChar.startFrame)
        assertEquals(origClip.durationFrames, swappedChar.durationFrames)
        assertEquals(origClip.keyframes, swappedChar.keyframes)
        assertEquals(origClip.motionPathControlPoints, swappedChar.motionPathControlPoints)
        assertEquals(origClip.orientation, swappedChar.orientation)
        assertEquals(origClip.animationPresetType, swappedChar.animationPresetType)
        assertEquals(origClip.facialExpression, swappedChar.facialExpression)
        assertEquals(origClip.propSwayStrength, swappedChar.propSwayStrength, 0.0001f)
        assertEquals(origClip.tearsConfig, swappedChar.tearsConfig)
        assertEquals(origClip.shadowConfig, swappedChar.shadowConfig)
        assertEquals(origClip.colorAdjust, swappedChar.colorAdjust)
        assertEquals(origClip.maskConfig, swappedChar.maskConfig)

        val swappedImgDoc = ProjectMutator.replaceClipMedia(swappedCharDoc, imgClip.id, "/new/bg.png", "bg_new", "New BG")
        val swappedImg = swappedImgDoc.activeScene.clips.first { it.id == imgClip.id }
        assertEquals("/new/bg.png", swappedImg.imagePath)
        assertEquals("bg_new", swappedImg.bundledBgKey)
        assertNull(swappedImg.inlineSvgAssetId)
        assertEquals("New BG", swappedImg.name)
        assertEquals(imgClip.keyframes, swappedImg.keyframes)
        assertEquals(imgClip.shadowConfig, swappedImg.shadowConfig)
        assertEquals(imgClip.colorAdjust, swappedImg.colorAdjust)
    }

    @Test
    fun `Pro Tools 2 - ClipVisualFxEngine Tears FX is deterministic per frame and animates downward across all styles`() {
        val offDrops = com.example.engine.ClipVisualFxEngine.computeTearDrops(
            TearsFxConfig(TearsFxStyle.OFF, 0.8f, 1.0f), eyeX = 10f, eyeY = 2f, scale = 1.5f, globalFrame = 15, fps = 24, seed = 42
        )
        assertTrue(offDrops.isEmpty())

        val glistening = com.example.engine.ClipVisualFxEngine.computeTearDrops(
            TearsFxConfig(TearsFxStyle.GLISTENING, 0.8f, 1.0f), eyeX = 10f, eyeY = 2f, scale = 1.5f, globalFrame = 15, fps = 24, seed = 42
        )
        assertTrue("Glistening should produce eye pool + corner glint", glistening.size >= 2)

        val flowingA = com.example.engine.ClipVisualFxEngine.computeTearDrops(
            TearsFxConfig(TearsFxStyle.FLOWING, 0.9f, 1.2f), eyeX = 10f, eyeY = 2f, scale = 1.5f, globalFrame = 20, fps = 24, seed = 7
        )
        val flowingB = com.example.engine.ClipVisualFxEngine.computeTearDrops(
            TearsFxConfig(TearsFxStyle.FLOWING, 0.9f, 1.2f), eyeX = 10f, eyeY = 2f, scale = 1.5f, globalFrame = 20, fps = 24, seed = 7
        )
        val flowingLater = com.example.engine.ClipVisualFxEngine.computeTearDrops(
            TearsFxConfig(TearsFxStyle.FLOWING, 0.9f, 1.2f), eyeX = 10f, eyeY = 2f, scale = 1.5f, globalFrame = 26, fps = 24, seed = 7
        )
        assertEquals("Same frame & seed must be bit-identical for preview & export", flowingA, flowingB)
        assertNotEquals("Advancing frame must animate falling teardrops", flowingA, flowingLater)

        val heavy = com.example.engine.ClipVisualFxEngine.computeTearDrops(
            TearsFxConfig(TearsFxStyle.HEAVY_STREAM, 1.0f, 1.5f), eyeX = 10f, eyeY = 2f, scale = 1.5f, globalFrame = 30, fps = 24, seed = 9
        )
        assertTrue("Heavy stream must include animated stream ribbon", heavy.any { it.isStreamRibbon })
        assertTrue("Heavy stream must include splash droplets", heavy.count { !it.isStreamRibbon } >= 4)
    }

    @Test
    fun `Pro Tools 3 - Dynamic Ground Shadow scales and softens naturally when character jumps`() {
        val cfg = ShadowConfig(enabled = true, mode = ShadowMode.GROUND_ELLIPSE, opacity = 0.50f, softness = 0.35f, scaleX = 1.0f, scaleY = 0.32f)
        val grounded = com.example.engine.ClipVisualFxEngine.computeShadowGeometry(
            config = cfg, isCharacter = true, baseScale = 2.0f, bodyBobY = 0f, clipWidthPx = 220f, clipHeightPx = 300f
        )
        val midJump = com.example.engine.ClipVisualFxEngine.computeShadowGeometry(
            config = cfg, isCharacter = true, baseScale = 2.0f, bodyBobY = -42f, clipWidthPx = 220f, clipHeightPx = 300f
        )
        assertTrue("Jump must shrink shadow radiusX", midJump.radiusX < grounded.radiusX)
        assertTrue("Jump must shrink shadow radiusY", midJump.radiusY < grounded.radiusY)
        assertTrue("Jump must reduce shadow opacity naturally", midJump.effectiveOpacity < grounded.effectiveOpacity)
        assertTrue("Jump must soften shadow penumbra", midJump.softness > grounded.softness)
        assertEquals(1f, grounded.jumpFactor, 0.001f)
        assertTrue(midJump.jumpFactor in 0.42f..0.9f)

        val drop = com.example.engine.ClipVisualFxEngine.computeShadowGeometry(
            config = cfg.copy(mode = ShadowMode.DROP_SHADOW),
            isCharacter = false, baseScale = 1.5f, bodyBobY = 0f, clipWidthPx = 240f, clipHeightPx = 200f
        )
        assertFalse(drop.isGroundMode)
        assertTrue(drop.centerX > 0f && drop.centerY > 0f)
    }

    @Test
    fun `Pro Tools 4 - Colour Adjust 4x5 ColorMatrix computes identity, brightness, contrast, grayscale saturation, hue, and warmth`() {
        val id = com.example.engine.ClipVisualFxEngine.buildColorAdjustMatrix(ClipColorAdjust())
        assertEquals(20, id.size)
        assertEquals(1f, id[0], 0.0001f)
        assertEquals(1f, id[6], 0.0001f)
        assertEquals(1f, id[12], 0.0001f)
        assertEquals(0f, id[4], 0.0001f)

        val bright = com.example.engine.ClipVisualFxEngine.buildColorAdjustMatrix(ClipColorAdjust(brightness = 0.5f))
        assertEquals(60f, bright[4], 0.1f)
        assertEquals(60f, bright[9], 0.1f)
        assertEquals(60f, bright[14], 0.1f)

        val contrast = com.example.engine.ClipVisualFxEngine.buildColorAdjustMatrix(ClipColorAdjust(contrast = 1.5f))
        assertEquals(1.5f, contrast[0], 0.001f)
        assertEquals(-64f, contrast[4], 0.1f)

        val gray = com.example.engine.ClipVisualFxEngine.buildColorAdjustMatrix(ClipColorAdjust(saturation = 0f))
        // All 3 RGB rows become identical luminance weights [0.213, 0.715, 0.072]
        assertEquals(gray[0], gray[5], 0.001f)
        assertEquals(gray[1], gray[6], 0.001f)
        assertEquals(gray[2], gray[7], 0.001f)

        val warm = com.example.engine.ClipVisualFxEngine.buildColorAdjustMatrix(ClipColorAdjust(warmth = 0.8f))
        assertTrue("Positive warmth boosts red gain above blue gain", warm[0] > warm[12])
        assertTrue("Positive warmth shifts red offset above blue offset", warm[4] > warm[14])

        val hue = com.example.engine.ClipVisualFxEngine.buildColorAdjustMatrix(ClipColorAdjust(hueDeg = 90f))
        assertTrue("Hue rotation 90° mixes blue into red channel", kotlin.math.abs(hue[2]) > 0.9f)
        assertTrue("Hue rotation 90° mixes green into blue channel", kotlin.math.abs(hue[11]) > 1.0f)
        assertEquals("Hue rotation preserves white point row sum", 1f, hue[0] + hue[1] + hue[2], 0.001f)
    }

    @Test
    fun `Pro Tools 5 - Duplicate Layer deep-copies cleanly above original and all 7 features are registered in Blueprint and DirectorScript`() {
        val baseLayer = AnimationLayer(id = "l_hero", name = "Hero", type = LayerType.CHARACTER, orderIndex = 0, parallaxDepth = 1.25f)
        val topLayer = AnimationLayer(id = "l_fg", name = "Foreground", type = LayerType.IMAGE, orderIndex = 1)
        val heroClip = TimelineClip(
            id = "c_hero",
            layerId = baseLayer.id,
            name = "Hero Walk",
            characterId = BuiltInCatalog.builtInCharacters.first().id,
            tearsConfig = TearsFxConfig(TearsFxStyle.FLOWING, 0.7f, 1.1f),
            shadowConfig = ShadowConfig(enabled = true),
            colorAdjust = ClipColorAdjust(contrast = 1.25f),
            maskConfig = ClipMaskConfig(type = ClipMaskType.LINEAR_SPLIT, width = 0.6f, invert = true)
        )
        val doc = ProjectDocument(
            scenes = listOf(AnimationScene(layers = listOf(baseLayer, topLayer), clips = listOf(heroClip)))
        )

        val (dupDoc, dupLayerId) = ProjectMutator.duplicateLayer(doc, baseLayer.id)
        assertNotNull(dupLayerId)
        val sortedLayers = dupDoc.activeScene.layers.sortedBy { it.orderIndex }
        assertEquals(3, sortedLayers.size)
        assertEquals("l_hero", sortedLayers[0].id)
        assertEquals(dupLayerId, sortedLayers[1].id)
        assertEquals("Hero (Copy)", sortedLayers[1].name)
        assertEquals(1.25f, sortedLayers[1].parallaxDepth, 0.001f)
        assertEquals("l_fg", sortedLayers[2].id)

        val dupClips = dupDoc.activeScene.clips.filter { it.layerId == dupLayerId }
        assertEquals(1, dupClips.size)
        assertNotEquals(heroClip.id, dupClips.first().id)
        assertEquals(heroClip.tearsConfig, dupClips.first().tearsConfig)
        assertEquals(heroClip.shadowConfig, dupClips.first().shadowConfig)
        assertEquals(heroClip.colorAdjust, dupClips.first().colorAdjust)
        assertEquals(heroClip.maskConfig, dupClips.first().maskConfig)

        // Verify StudioCapabilityRegistry & AIBlueprintExporter dynamically expose all 7 features
        val caps = com.example.engine.StudioCapabilityRegistry.introspect()
        assertTrue(caps.tearsFxStyles.any { it.id == "HEAVY_STREAM" })
        assertTrue(caps.shadowModes.any { it.id == "GROUND_ELLIPSE" })
        assertTrue(caps.maskTypes.any { it.id == "RECTANGLE" } && caps.maskTypes.any { it.id == "LINEAR_SPLIT" })
        val opIds = caps.clipOperations.map { it.id }.toSet()
        assertTrue(setOf("replace", "tears", "shadow", "adjust", "mask", "duplicateLayer", "sort").all { it in opIds })

        val bp = com.example.engine.AIBlueprintExporter.buildBlueprint(dupDoc, BuiltInCatalog.builtInCharacters, BuiltInCatalog.bundledMediaAssets)
        assertTrue(bp.capabilities.tearsFxStyles.contains("FLOWING"))
        assertTrue(bp.capabilities.shadowModes.contains("DROP_SHADOW"))
        assertTrue(bp.capabilities.maskTypes.contains("LINEAR_SPLIT"))

        // Verify DirectorScriptReceiver parses clip visual FX + executes replace, duplicateLayer, and sort
        val char1 = BuiltInCatalog.builtInCharacters[0].id
        val char2 = BuiltInCatalog.builtInCharacters[1].id
        val scriptJson = """
            {
              "format": "framecraft.director_script",
              "schemaVersion": 3,
              "scenes": [{
                "name": "FX Test Scene",
                "durationSec": 4.0,
                "layers": [
                  { "id": "lay_a", "name": "Actor A", "type": "CHARACTER", "zIndex": 0 },
                  { "id": "lay_b", "name": "Overlay", "type": "TEXT", "zIndex": 1 }
                ],
                "clips": [
                  {
                    "id": "clip_a",
                    "layerId": "lay_a",
                    "characterId": "$char1",
                    "startSec": 0.0,
                    "durationSec": 3.0,
                    "tears": { "style": "HEAVY_STREAM", "intensity": 0.9, "speed": 1.4 },
                    "shadow": { "enabled": true, "mode": "GROUND_ELLIPSE", "opacity": 0.55 },
                    "colorAdjust": { "brightness": 0.15, "contrast": 1.3, "warmth": 0.4 },
                    "mask": { "type": "RECTANGLE", "width": 0.8, "height": 0.7, "feather": 0.2, "invert": true }
                  }
                ],
                "clipOperations": [
                  { "op": "replace", "targetClipId": "clip_a", "newCharacterId": "$char2" },
                  { "op": "duplicateLayer", "targetLayerId": "lay_a", "newLayerId": "lay_a_dup" },
                  { "op": "sort", "targetLayerId": "lay_a", "zOrderAction": "BRING_TO_FRONT" }
                ]
              }]
            }
        """.trimIndent()
        val compileResult = com.example.engine.DirectorScriptReceiver.compileIntoProject(
            context = null,
            scriptJson = scriptJson,
            currentProject = dupDoc,
            existingCharacters = BuiltInCatalog.builtInCharacters,
            existingMediaAssets = BuiltInCatalog.bundledMediaAssets,
            importMode = com.example.engine.DirectorScriptImportMode.REPLACE_TIMELINE
        )
        assertTrue("Script compile failed: ${compileResult.exceptionOrNull()?.message}", compileResult.isSuccess)
        val applied = compileResult.getOrThrow()
        assertTrue("Script validation failed: ${applied.report.errors}", applied.report.isValid)
        val outScene = applied.project.scenes.first()
        val outClip = outScene.clips.first { it.id == "clip_a" }
        assertEquals(char2, outClip.characterId)
        assertEquals(TearsFxStyle.HEAVY_STREAM, outClip.tearsConfig.style)
        assertTrue(outClip.shadowConfig.enabled)
        assertEquals(1.3f, outClip.colorAdjust.contrast, 0.001f)
        assertEquals(ClipMaskType.RECTANGLE, outClip.effectiveMaskType)
        assertTrue(outClip.maskConfig.invert)
        assertTrue("Duplicate layer must exist", outScene.layers.any { it.id == "lay_a_dup" })
        assertEquals("Sort BRING_TO_FRONT must move lay_a to highest orderIndex", "lay_a", outScene.layers.maxByOrNull { it.orderIndex }?.id)
    }

    @Test
    fun `Stage 1A - Pro Control Modular Action Catalog has 100+ Desi and Story presets with valid silhouette kinematics`() {
        val all = com.example.engine.ModularActionEngine.catalog
        val exprs = com.example.engine.ModularActionEngine.actionsForTrack(com.example.engine.ModularActionTrack.FACIAL)
        val hands = com.example.engine.ModularActionEngine.actionsForTrack(com.example.engine.ModularActionTrack.HANDS)
        val legs = com.example.engine.ModularActionEngine.actionsForTrack(com.example.engine.ModularActionTrack.LEGS)

        assertTrue("Expected 35+ Expressions, got ${exprs.size}", exprs.size >= 35)
        assertTrue("Expected 50+ Hand Parts, got ${hands.size}", hands.size >= 50)
        assertTrue("Expected 25+ Leg Parts, got ${legs.size}", legs.size >= 25)
        assertEquals("All preset IDs must be unique", all.size, all.map { it.id }.toSet().size)

        // Verify authentic Desi/Story presets & search
        assertTrue(com.example.engine.ModularActionEngine.search("ashirwaad").size >= 3)
        assertTrue(com.example.engine.ModularActionEngine.search("matka").isNotEmpty())
        assertTrue(com.example.engine.ModularActionEngine.search("bori").size >= 3)
        assertTrue(com.example.engine.ModularActionEngine.search("namaskar").size >= 2)
        assertTrue(com.example.engine.ModularActionEngine.search("roti").isNotEmpty())
        assertTrue(com.example.engine.ModularActionEngine.search("bail gadi").size >= 2)

        // Verify every single preset evaluates deterministically and produces valid silhouette geometry
        for (act in all) {
            val s0 = com.example.engine.ModularActionEngine.evaluateAtProgress(act, 0.25f)
            val s1 = com.example.engine.ModularActionEngine.evaluateAtProgress(act, 0.55f)
            assertEquals(act.track, s0.track)
            assertFalse(s0.leftArmDelta.isNaN() || s0.rightLegDelta.isNaN() || s1.headNodDelta.isNaN())
            val g0 = com.example.ui.screens.editor.SilhouettePreviewGeometryBuilder.build(act, s0, 140f, 90f)
            assertTrue(g0.headRadius > 0f && g0.headCenterX.isFinite() && g0.rightHand.first.isFinite())
        }

        // Verify animated limb motion between two phases on a Hand preset and a Leg preset
        val clap = com.example.engine.ModularActionEngine.findById("hand_cutting_tree")!!
        val gA = com.example.ui.screens.editor.SilhouettePreviewGeometryBuilder.build(
            clap, com.example.engine.ModularActionEngine.evaluateAtProgress(clap, 0.25f), 140f, 90f
        )
        val gB = com.example.ui.screens.editor.SilhouettePreviewGeometryBuilder.build(
            clap, com.example.engine.ModularActionEngine.evaluateAtProgress(clap, 0.75f), 140f, 90f
        )
        assertNotEquals("Animated silhouette hands must move between phases", gA.rightHand.second, gB.rightHand.second, 1f)
    }

    @Test
    fun `Stage 1B - ModularControlSelection layers Expression, Hands, Legs, Speaking, Eyeblink non-destructively and integrates with Blueprint and DirectorScript`() {
        val baseLayer = AnimationLayer(id = "l_char", name = "Actor", type = LayerType.CHARACTER, orderIndex = 0)
        val clip = TimelineClip(
            id = "c_actor",
            layerId = baseLayer.id,
            name = "Hero",
            characterId = BuiltInCatalog.builtInCharacters.first().id,
            startFrame = 0,
            durationFrames = 96
        )
        val doc = ProjectDocument(scenes = listOf(AnimationScene(layers = listOf(baseLayer), clips = listOf(clip))))

        val selection = com.example.engine.ModularControlSelection(
            expressionId = "expr_look_right",
            handId = "hand_namaskar",
            legId = "leg_namaskar_sitting",
            speakingMode = CharacterSpeakingMode.YES,
            eyeblinkMode = CharacterEyeblinkMode.DISABLE
        )
        val updatedDoc = com.example.engine.ModularActionEngine.applyModularControlConfigToProject(doc, clip.id, selection)
        val updatedClip = updatedDoc.activeScene.clips.first()
        assertEquals("expr_look_right", updatedClip.modularExpressionId)
        assertEquals("hand_namaskar", updatedClip.modularHandId)
        assertEquals("leg_namaskar_sitting", updatedClip.modularLegId)
        assertEquals(CharacterSpeakingMode.YES, updatedClip.speakingMode)
        assertEquals(CharacterEyeblinkMode.DISABLE, updatedClip.eyeblinkMode)

        // Evaluate via KeyframeEvaluator at frame 12 -> all 3 tracks + Speaking YES + Eyeblink DISABLE active together
        val eval = KeyframeEvaluator.evaluate(updatedClip, 12)
        val blend = KeyframeEvaluator.evaluateSimultaneousCharacterTracks(updatedClip, eval, globalFrame = 12, fps = 24)
        assertTrue("Namaskar hands should bend forearms inward", kotlin.math.abs(blend.blendedPoseOffsets.leftForearmOffset) > 40f)
        assertTrue("Namaskar sitting should bend legs", kotlin.math.abs(blend.blendedPoseOffsets.leftKneeOffset) > 40f)
        assertTrue("Look right should shift gaze X positive", (blend.effectiveGazeX ?: 0f) > 0.3f)
        assertTrue("Speaking YES must activate lipSyncActive", blend.lipSyncActive)
        assertEquals("Eyeblink DISABLE must force effectiveBlink=false", false, blend.effectiveBlink)

        // Eyeblink EYE_CLOSED overrides to closed eyes
        val closedClip = updatedClip.copy(eyeblinkMode = CharacterEyeblinkMode.EYE_CLOSED)
        val closedBlend = KeyframeEvaluator.evaluateSimultaneousCharacterTracks(closedClip, eval, globalFrame = 12, fps = 24)
        assertEquals(true, closedBlend.effectiveBlink)
        assertEquals(EyeShapeOverride.CLOSED_SLEEP, closedBlend.effectiveEyeOverride)

        // Dynamic StudioCapabilityRegistry + AIBlueprintExporter + DirectorScriptReceiver check
        val caps = com.example.engine.StudioCapabilityRegistry.introspect()
        assertTrue(caps.modularActions.any { it.id == "hand_carrying_water_matka" })
        assertTrue(caps.speakingModes.any { it.id == "AUTO_LIP_SYNC" })
        assertTrue(caps.eyeblinkModes.any { it.id == "EYE_CLOSED" })

        val bp = com.example.engine.AIBlueprintExporter.buildBlueprint(updatedDoc, BuiltInCatalog.builtInCharacters, BuiltInCatalog.bundledMediaAssets)
        assertTrue(bp.capabilities.modularActions.contains("leg_kicking_ball"))
        assertTrue(bp.capabilities.speakingModes.contains("YES"))
        assertTrue(bp.capabilities.eyeblinkModes.contains("DISABLE"))

        val charId = BuiltInCatalog.builtInCharacters.first().id
        val scriptJson = """
            {
              "format": "framecraft.director_script",
              "schemaVersion": 3,
              "scenes": [{
                "name": "Desi Story Scene",
                "durationSec": 4.0,
                "layers": [{ "id": "l1", "name": "Farmer", "type": "CHARACTER", "zIndex": 0 }],
                "clips": [{
                  "id": "farmer_clip",
                  "layerId": "l1",
                  "characterId": "$charId",
                  "startSec": 0.0,
                  "durationSec": 3.0,
                  "modularExpressionId": "expr_gods_ashirwaad",
                  "modularHandId": "hand_gods_ashirwaad",
                  "modularLegId": "leg_gods_ashirwaad",
                  "speakingMode": "YES",
                  "eyeblinkMode": "EYE_CLOSED"
                }]
              }]
            }
        """.trimIndent()
        val compiled = com.example.engine.DirectorScriptReceiver.compileIntoProject(
            context = null,
            scriptJson = scriptJson,
            currentProject = doc,
            existingCharacters = BuiltInCatalog.builtInCharacters,
            existingMediaAssets = BuiltInCatalog.bundledMediaAssets,
            importMode = com.example.engine.DirectorScriptImportMode.REPLACE_TIMELINE
        ).getOrThrow()
        val compiledClip = compiled.project.scenes.first().clips.first { it.id == "farmer_clip" }
        assertEquals("expr_gods_ashirwaad", compiledClip.modularExpressionId)
        assertEquals("hand_gods_ashirwaad", compiledClip.modularHandId)
        assertEquals("leg_gods_ashirwaad", compiledClip.modularLegId)
        assertEquals(CharacterSpeakingMode.YES, compiledClip.speakingMode)
        assertEquals(CharacterEyeblinkMode.EYE_CLOSED, compiledClip.eyeblinkMode)
    }

    @Test
    fun `Stage 2A - MediaPipeMocapRetargeter retargets 52 ARKit blendshapes, 478 iris landmarks, and 4x4 head matrix`() {
        // Build 478 neutral face landmarks with irises shifted to the right (gazeX > 0.3)
        val landmarks = MutableList(478) { com.example.engine.MocapLandmark3D(0.5f, 0.5f, 0f) }
        landmarks[33] = com.example.engine.MocapLandmark3D(0.35f, 0.42f, 0f)   // left eye outer
        landmarks[133] = com.example.engine.MocapLandmark3D(0.45f, 0.42f, 0f)  // left eye inner
        landmarks[468] = com.example.engine.MocapLandmark3D(0.43f, 0.42f, 0f)  // left iris right-shifted
        landmarks[362] = com.example.engine.MocapLandmark3D(0.55f, 0.42f, 0f)  // right eye inner
        landmarks[263] = com.example.engine.MocapLandmark3D(0.65f, 0.42f, 0f)  // right eye outer
        landmarks[473] = com.example.engine.MocapLandmark3D(0.63f, 0.42f, 0f)  // right iris right-shifted

        // 4x4 column-major matrix with ~18 deg roll
        val rollRad = Math.toRadians(18.0)
        val cosR = kotlin.math.cos(rollRad).toFloat()
        val sinR = kotlin.math.sin(rollRad).toFloat()
        val headMatrix = floatArrayOf(
            cosR, sinR, 0f, 0f,
            -sinR, cosR, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        // Shocked / O-mouth + raised eyebrows (inner + outer up) + blinking off
        val shockSample = com.example.engine.MediaPipeMocapRetargeter.retargetFace(
            blendshapes = mapOf(
                "jawOpen" to 0.70f,
                "mouthFunnel" to 0.55f,
                "eyeWideLeft" to 0.65f,
                "eyeWideRight" to 0.65f,
                "browInnerUp" to 0.60f,
                "browOuterUpLeft" to 0.58f,
                "browOuterUpRight" to 0.58f,
                "eyeBlinkLeft" to 0.05f,
                "eyeBlinkRight" to 0.05f
            ),
            faceLandmarks = landmarks,
            headMatrix4x4 = headMatrix
        )
        assertFalse(shockSample.isBlinking)
        assertTrue("Iris gazeX should shift right, got ${shockSample.gazeTarget.x}", shockSample.gazeTarget.x > 0.4f)
        assertEquals(18f, shockSample.headTiltDeg, 1.5f)
        assertEquals(MouthShapeOverride.SHOCK_O, shockSample.mouthOverride)
        assertEquals("Lips_O", shockSample.visemeCode)
        assertEquals("mth4", shockSample.mthCode)
        assertEquals(EyebrowShapeOverride.RAISED_SURPRISE, shockSample.eyebrowOverride)
        assertEquals(FacialExpression.SHOCKED, shockSample.expression)

        // Worried/Sad inner-only brow lift
        val worriedSample = com.example.engine.MediaPipeMocapRetargeter.retargetFace(
            blendshapes = mapOf("browInnerUp" to 0.62f, "browOuterUpLeft" to 0.05f, "browOuterUpRight" to 0.05f)
        )
        assertEquals(EyebrowShapeOverride.WORRIED_SAD, worriedSample.eyebrowOverride)

        // Angry + closed eyes blink + furrowed brows
        val angryBlink = com.example.engine.MediaPipeMocapRetargeter.retargetFace(
            blendshapes = mapOf(
                "eyeBlinkLeft" to 0.85f,
                "eyeBlinkRight" to 0.80f,
                "browDownLeft" to 0.72f,
                "browDownRight" to 0.75f,
                "jawOpen" to 0.04f
            )
        )
        assertTrue(angryBlink.isBlinking)
        assertEquals(EyeShapeOverride.CLOSED_SLEEP, angryBlink.eyeOverride)
        assertEquals(EyebrowShapeOverride.FURROWED_ANGRY, angryBlink.eyebrowOverride)
        assertEquals(FacialExpression.ANGRY, angryBlink.expression)
        assertEquals("Lips_M", angryBlink.visemeCode)
        assertEquals("mth1", angryBlink.mthCode)
    }

    @Test
    fun `Stage 2B - MediaPipeMocapRetargeter retargets 33 pose landmarks within AnatomicalJointLimits and classifies 6 palm gestures`() {
        // 33 pose landmarks: raised arms and bent knee
        val pose = MutableList(33) { com.example.engine.MocapLandmark3D(0.5f, 0.5f, 0f, 1f) }
        pose[0] = com.example.engine.MocapLandmark3D(0.50f, 0.16f, 0f, 1f)   // nose
        pose[11] = com.example.engine.MocapLandmark3D(0.42f, 0.30f, 0f, 1f)  // L shoulder
        pose[12] = com.example.engine.MocapLandmark3D(0.58f, 0.30f, 0f, 1f)  // R shoulder
        pose[13] = com.example.engine.MocapLandmark3D(0.28f, 0.18f, 0f, 1f)  // L elbow raised
        pose[15] = com.example.engine.MocapLandmark3D(0.22f, 0.08f, 0f, 1f)  // L wrist high
        pose[14] = com.example.engine.MocapLandmark3D(0.72f, 0.22f, 0f, 1f)  // R elbow raised
        pose[16] = com.example.engine.MocapLandmark3D(0.78f, 0.10f, 0f, 1f)  // R wrist high
        pose[23] = com.example.engine.MocapLandmark3D(0.45f, 0.58f, 0f, 1f)  // L hip
        pose[24] = com.example.engine.MocapLandmark3D(0.55f, 0.58f, 0f, 1f)  // R hip
        pose[25] = com.example.engine.MocapLandmark3D(0.42f, 0.76f, 0f, 1f)  // L knee
        pose[27] = com.example.engine.MocapLandmark3D(0.45f, 0.94f, 0f, 1f)  // L ankle
        pose[26] = com.example.engine.MocapLandmark3D(0.58f, 0.76f, 0f, 1f)  // R knee
        pose[28] = com.example.engine.MocapLandmark3D(0.56f, 0.94f, 0f, 1f)  // R ankle

        val offsets = com.example.engine.MediaPipeMocapRetargeter.retargetPose(
            poseLandmarks = pose,
            enforceJointLimits = true,
            orientation = CharacterOrientation.FRONT
        )
        val reClamped = com.example.engine.IkRigSolver.clampPoseOffsets(offsets, true, CharacterOrientation.FRONT)
        assertEquals(reClamped.headNodOffset, offsets.headNodOffset, 0.001f)
        assertEquals(reClamped.torsoTiltOffset, offsets.torsoTiltOffset, 0.001f)
        assertEquals(reClamped.leftArmOffset, offsets.leftArmOffset, 0.001f)
        assertEquals(reClamped.rightArmOffset, offsets.rightArmOffset, 0.001f)
        assertEquals(reClamped.leftLegOffset, offsets.leftLegOffset, 0.001f)

        // Helper to build synthetic 21-point hand with chosen fingers extended or curled
        fun buildHand(
            originX: Float = 0.5f,
            indexExt: Boolean,
            middleExt: Boolean,
            ringExt: Boolean,
            pinkyExt: Boolean,
            pinchThumbToIndex: Boolean = false
        ): List<com.example.engine.MocapLandmark3D> {
            val h = MutableList(21) { com.example.engine.MocapLandmark3D(originX, 0.60f, 0f) }
            h[0] = com.example.engine.MocapLandmark3D(originX, 0.70f, 0f) // wrist
            h[9] = com.example.engine.MocapLandmark3D(originX, 0.56f, 0f) // palm center
            fun setFinger(pipIdx: Int, tipIdx: Int, xOff: Float, extended: Boolean) {
                h[pipIdx] = com.example.engine.MocapLandmark3D(originX + xOff, 0.52f, 0f)
                h[tipIdx] = com.example.engine.MocapLandmark3D(originX + xOff, if (extended) 0.34f else 0.62f, 0f)
            }
            setFinger(6, 8, -0.04f, indexExt)
            setFinger(10, 12, 0.0f, middleExt)
            setFinger(14, 16, 0.04f, ringExt)
            setFinger(18, 20, 0.08f, pinkyExt)
            h[4] = if (pinchThumbToIndex) {
                com.example.engine.MocapLandmark3D(h[8].x + 0.01f, h[8].y + 0.01f, 0f)
            } else {
                com.example.engine.MocapLandmark3D(originX - 0.14f, 0.58f, 0f)
            }
            return h
        }

        val openHand = buildHand(indexExt = true, middleExt = true, ringExt = true, pinkyExt = true)
        val fistHand = buildHand(indexExt = false, middleExt = false, ringExt = false, pinkyExt = false)
        val pointHand = buildHand(indexExt = true, middleExt = false, ringExt = false, pinkyExt = false)
        val peaceHand = buildHand(indexExt = true, middleExt = true, ringExt = false, pinkyExt = false)
        val holdHand = buildHand(indexExt = false, middleExt = false, ringExt = false, pinkyExt = false, pinchThumbToIndex = true)
        val namaskarLeft = buildHand(originX = 0.48f, indexExt = true, middleExt = true, ringExt = true, pinkyExt = true)
        val namaskarRight = buildHand(originX = 0.52f, indexExt = true, middleExt = true, ringExt = true, pinkyExt = true)

        assertEquals(com.example.engine.MocapPalmGesture.OPEN, com.example.engine.MediaPipeMocapRetargeter.classifyPalmGesture(openHand))
        assertEquals(com.example.engine.MocapPalmGesture.FIST, com.example.engine.MediaPipeMocapRetargeter.classifyPalmGesture(fistHand))
        assertEquals(com.example.engine.MocapPalmGesture.POINT, com.example.engine.MediaPipeMocapRetargeter.classifyPalmGesture(pointHand))
        assertEquals(com.example.engine.MocapPalmGesture.PEACE, com.example.engine.MediaPipeMocapRetargeter.classifyPalmGesture(peaceHand))
        assertEquals(com.example.engine.MocapPalmGesture.HOLD, com.example.engine.MediaPipeMocapRetargeter.classifyPalmGesture(holdHand))
        assertEquals(
            com.example.engine.MocapPalmGesture.NAMASKAR,
            com.example.engine.MediaPipeMocapRetargeter.classifyPalmGesture(namaskarLeft, namaskarRight)
        )
    }

    @Test
    fun `Stage 2C - MocapEmaSmoother reduces high-frequency jitter and baker stamps selected tracks onto timeline`() {
        val smoother = com.example.engine.MocapEmaSmoother(initialAlpha = 0.30f)
        val base = com.example.engine.MocapRetargetedFrame(
            timestampMs = 0L,
            poseOffsets = PoseKeyframeOffsets(leftArmOffset = 0f),
            hasPoseData = true
        )
        val spike = com.example.engine.MocapRetargetedFrame(
            timestampMs = 40L,
            poseOffsets = PoseKeyframeOffsets(leftArmOffset = 100f),
            hasPoseData = true
        )
        val s0 = smoother.smooth(base)
        val s1 = smoother.smooth(spike)
        val s2 = smoother.smooth(spike.copy(timestampMs = 80L))
        assertEquals(0f, s0.poseOffsets.leftArmOffset, 0.01f)
        assertEquals(30f, s1.poseOffsets.leftArmOffset, 0.5f)
        assertTrue("EMA should converge monotonically toward target", s2.poseOffsets.leftArmOffset > s1.poseOffsets.leftArmOffset && s2.poseOffsets.leftArmOffset < 100f)

        // Bake 6 captured frames at 24fps (step=2) with Legs DISABLED and Expression+Hands ENABLED
        val charLayer = AnimationLayer(id = "l_mocap", name = "Mocap Actor", type = LayerType.CHARACTER, orderIndex = 0)
        val clip = TimelineClip(
            id = "c_mocap",
            layerId = charLayer.id,
            name = "Hero",
            characterId = BuiltInCatalog.builtInCharacters.first().id,
            startFrame = 0,
            durationFrames = 96
        )
        val doc = ProjectDocument(fps = 24, scenes = listOf(AnimationScene(layers = listOf(charLayer), clips = listOf(clip))))
        val captured = (0 until 6).map { i ->
            com.example.engine.MocapRetargetedFrame(
                timestampMs = i * 84L, // ~2 frames apart at 24fps
                poseOffsets = PoseKeyframeOffsets(
                    headNodOffset = 12f,
                    leftArmOffset = 45f,
                    leftLegOffset = 50f
                ),
                expression = FacialExpression.HAPPY,
                mouthOverride = MouthShapeOverride.SMILE,
                handPose = HandPoseType.POINT,
                hasFaceData = true,
                hasPoseData = true,
                hasHandData = true
            )
        }
        val (bakedDoc, stampedCount) = com.example.engine.MediaPipeMocapRetargeter.bakeMocapRecordingToProject(
            project = doc,
            clipId = clip.id,
            startGlobalFrame = 10,
            frames = captured,
            config = com.example.engine.MocapBakeConfig(
                sampleEveryNFrames = 2,
                bakeIkPose = true,
                bakeExpression = true,
                bakeHands = true,
                bakeLegs = false,
                enforceJointLimits = true
            ),
            fps = 24
        )
        assertTrue("Should bake at least 3 sampled keyframes, got $stampedCount", stampedCount >= 3)
        val bakedClip = bakedDoc.activeScene.clips.first { it.id == clip.id }
        val evalAt10 = KeyframeEvaluator.evaluate(bakedClip, 10)
        assertEquals(FacialExpression.HAPPY, evalAt10.expressionOverride)
        assertEquals(MouthShapeOverride.SMILE, evalAt10.mouthShapeOverride)
        assertEquals(HandPoseType.POINT, evalAt10.handPoseOverride)
        assertTrue("Hands track enabled -> leftArmOffset should be baked", evalAt10.poseOffsets.leftArmOffset > 30f)
        assertEquals("Legs track disabled -> leftLegOffset must remain untouched (0)", 0f, evalAt10.poseOffsets.leftLegOffset, 0.01f)
    }

    @Test
    fun `Pro Animation Polish - 180 presets loop seamlessly with exact Canvas FK signs, track isolation, and C1 jump`() {
        val all = com.example.engine.ModularActionEngine.catalog
        val exprs = com.example.engine.ModularActionEngine.actionsForTrack(com.example.engine.ModularActionTrack.FACIAL)
        val hands = com.example.engine.ModularActionEngine.actionsForTrack(com.example.engine.ModularActionTrack.HANDS)
        val legs = com.example.engine.ModularActionEngine.actionsForTrack(com.example.engine.ModularActionTrack.LEGS)

        assertTrue("Expected 50+ Expressions, got ${exprs.size}", exprs.size >= 50)
        assertTrue("Expected 85+ Hand Parts, got ${hands.size}", hands.size >= 85)
        assertTrue("Expected 38+ Leg Parts, got ${legs.size}", legs.size >= 38)
        assertEquals("All 180 preset IDs must be unique", all.size, all.map { it.id }.toSet().size)

        // 1. Every single preset must be seamless at loop boundary p=0f vs p=1f (no zero-snap pop!)
        for (act in all) {
            val s0 = com.example.engine.ModularActionEngine.evaluateAtProgress(act, 0f)
            val s1 = com.example.engine.ModularActionEngine.evaluateAtProgress(act, 1f)
            assertEquals("Loop discontinuity on leftArmDelta for ${act.id}", s0.leftArmDelta, s1.leftArmDelta, 0.5f)
            assertEquals("Loop discontinuity on rightArmDelta for ${act.id}", s0.rightArmDelta, s1.rightArmDelta, 0.5f)
            assertEquals("Loop discontinuity on leftLegDelta for ${act.id}", s0.leftLegDelta, s1.leftLegDelta, 0.5f)
            assertEquals("Loop discontinuity on bodyBobDelta for ${act.id}", s0.bodyBobDelta, s1.bodyBobDelta, 0.5f)
        }

        // 2. Held poses must NOT collapse to 0 at p=0f (former holdEnv bug!)
        val namaskar = com.example.engine.ModularActionEngine.findById("hand_namaskar")!!
        val nam0 = com.example.engine.ModularActionEngine.evaluateAtProgress(namaskar, 0f)
        assertTrue("Namaskar left forearm must fold inward (< -60 deg) at p=0, got ${nam0.leftForearmDelta}", nam0.leftForearmDelta < -60f)
        assertTrue("Namaskar right forearm must fold inward (> 60 deg) at p=0, got ${nam0.rightForearmDelta}", nam0.rightForearmDelta > 60f)

        // Exact Android Canvas FK check: Namaskar hands must be closer to center X than elbows!
        val namGeom = com.example.ui.screens.editor.SilhouettePreviewGeometryBuilder.build(namaskar, nam0, 140f, 90f)
        val cx = 70f
        assertTrue(
            "Left Namaskar hand (${namGeom.leftHand.first}) must fold inward toward center ($cx) compared to elbow (${namGeom.leftElbow.first})",
            kotlin.math.abs(namGeom.leftHand.first - cx) < kotlin.math.abs(namGeom.leftElbow.first - cx)
        )
        assertTrue(
            "Right Namaskar hand (${namGeom.rightHand.first}) must fold inward toward center ($cx) compared to elbow (${namGeom.rightElbow.first})",
            kotlin.math.abs(namGeom.rightHand.first - cx) < kotlin.math.abs(namGeom.rightElbow.first - cx)
        )

        // Single right arm silhouette metadata check
        val angryR = com.example.engine.ModularActionEngine.findById("hand_angry_r")!!
        assertEquals("single_right_arm", com.example.engine.ModularActionEngine.evaluateAtProgress(angryR, 0.3f).silhouetteStyle)
        val ashirwaadR = com.example.engine.ModularActionEngine.findById("hand_gods_ashirwaad_r")!!
        assertEquals("single_right_arm", com.example.engine.ModularActionEngine.evaluateAtProgress(ashirwaadR, 0.3f).silhouetteStyle)

        // 3. Track isolation & bodyBobDelta / squashX propagation through KeyframeEvaluator
        val layer = AnimationLayer(id = "l_iso", name = "Actor", type = LayerType.CHARACTER, orderIndex = 0)
        val clip = TimelineClip(
            id = "c_iso",
            layerId = layer.id,
            name = "Hero",
            characterId = BuiltInCatalog.builtInCharacters.first().id,
            animationPresetType = AnimationPresetType.WALK,
            modularHandId = "hand_namaskar",
            modularLegId = "leg_sitting",
            startFrame = 0,
            durationFrames = 72
        )
        val eval = KeyframeEvaluator.evaluate(clip, 10)
        val blend = KeyframeEvaluator.evaluateSimultaneousCharacterTracks(clip, eval, globalFrame = 10, fps = 24)
        assertTrue("overrideHandTrack must be true when modularHandId is set", blend.overrideHandTrack)
        assertTrue("overrideLegTrack must be true when modularLegId is set", blend.overrideLegTrack)
        assertTrue("leg_sitting must lower hips via positive bodyBobDelta, got ${blend.bodyBobDelta}", blend.bodyBobDelta > 10f)

        // 4. C1-continuous ProceduralRigRenderer.computePose(JUMPING) — zero 1-frame snap between 0.19f and 0.23f
        val jumpBefore = ProceduralRigRenderer.computePose(AnimationPresetType.JUMPING, 0.21f)
        val jumpAfter = ProceduralRigRenderer.computePose(AnimationPresetType.JUMPING, 0.23f)
        assertTrue(
            "JUMPING bodyBobY must transition smoothly across takeoff (delta=${kotlin.math.abs(jumpAfter.bodyBobY - jumpBefore.bodyBobY)})",
            kotlin.math.abs(jumpAfter.bodyBobY - jumpBefore.bodyBobY) < 8f
        )
        assertTrue(
            "JUMPING leftArmDeg must transition smoothly across takeoff (delta=${kotlin.math.abs(jumpAfter.leftArmDeg - jumpBefore.leftArmDeg)})",
            kotlin.math.abs(jumpAfter.leftArmDeg - jumpBefore.leftArmDeg) < 16f
        )
    }

    @Test
    fun `5-Angle Turnaround System, Soft-IK Reach Damping, and 206 Pro Presets work seamlessly`() {
        // 1. Unified 5-Angle CharacterAngleSystem checks
        assertEquals(5, com.example.engine.CharacterAngleSystem.turnaroundAngles.size)
        val front = com.example.engine.CharacterAngleSystem.profileFor(CharacterOrientation.FRONT, HumanBuildType.STANDARD)
        val q3Front = com.example.engine.CharacterAngleSystem.profileFor(CharacterOrientation.THREE_QUARTER_FRONT, HumanBuildType.STANDARD)
        val side = com.example.engine.CharacterAngleSystem.profileFor(CharacterOrientation.SIDE, HumanBuildType.STANDARD)
        val q3Back = com.example.engine.CharacterAngleSystem.profileFor(CharacterOrientation.THREE_QUARTER_BACK, HumanBuildType.STANDARD)
        val back = com.example.engine.CharacterAngleSystem.profileFor(CharacterOrientation.BACK, HumanBuildType.STANDARD)

        assertEquals(kotlin.math.abs(front.leftShoulderX), kotlin.math.abs(front.rightShoulderX), 0.01f)
        assertTrue("3/4 Front should foreshorten far shoulder X", kotlin.math.abs(q3Front.leftShoulderX) < kotlin.math.abs(q3Front.rightShoulderX))
        assertTrue("3/4 Front right eye should be foreshortened narrower than left eye", q3Front.rightEyeScaleX < q3Front.leftEyeScaleX)
        assertEquals(com.example.engine.HeadAngleProfileMode.SIDE_PROFILE, side.headProfileMode)
        assertTrue("Side view far-limb shading must darken far limbs (< 0.85)", side.leftArmDepthShade < 0.85f)
        assertFalse("3/4 Back hides face features", q3Back.showsFace)
        assertFalse("Back view hides face features", back.showsFace)
        assertEquals(com.example.engine.ShoeAngleView.REAR_HEEL, back.leftShoeView)

        // 2. IkRigSolver 5-angle asymmetric anchors + Pro Soft-IK over-reach damping (zero pop/NaN)
        val rig = BuiltInCatalog.builtInCharacters.first()
        val base = BuiltInCatalog.sceneTemplates.first().buildProject("Soft IK Test")
        val charClip = base.activeScene.clips.first { it.characterId != null }.copy(orientation = CharacterOrientation.THREE_QUARTER_FRONT)
        val eval = KeyframeEvaluator.evaluate(charClip, 0)
        val basePose = ProceduralRigRenderer.computePose(AnimationPresetType.IDLE, 0f)
        val (_, anchors3q) = com.example.engine.IkRigSolver.computeStageIkHandlesWithAnchors(
            clip = charClip,
            rig = rig,
            transform = eval,
            pose = basePose,
            stageWidth = 1000f,
            stageHeight = 562f
        )
        assertTrue(
            "Stage IK anchors must mirror 3/4 Front shoulder asymmetry",
            kotlin.math.abs(anchors3q.leftShoulderNx - eval.x) < kotlin.math.abs(anchors3q.rightShoulderNx - eval.x)
        )
        // Drag far beyond 150% arm reach -> Soft-IK must damp cleanly without NaN
        val overReachSolved = com.example.engine.IkRigSolver.solveIkDragToOffsets(
            handleId = IkHandleId.RIGHT_HAND,
            targetNx = (eval.x + 0.42f).coerceIn(0.05f, 0.98f),
            targetNy = (eval.y - 0.35f).coerceIn(0.05f, 0.98f),
            clip = charClip,
            rig = rig,
            transform = eval,
            basePose = basePose,
            currentOffsets = PoseKeyframeOffsets(),
            stageWidth = 1000f,
            stageHeight = 562f,
            bendFlip = false
        )
        assertFalse(overReachSolved.rightArmOffset.isNaN())
        assertFalse(overReachSolved.rightForearmOffset.isNaN())
        assertTrue("Soft-IK should keep subtle natural bend near full extension rather than rigid 0-deg lock", kotlin.math.abs(overReachSolved.rightForearmOffset) > 0.5f)

        // 3. Expanded 206-preset catalog checks (60 Expressions + 100 Hand Parts + 46 Leg Parts)
        val all = com.example.engine.ModularActionEngine.catalog
        val exprs = com.example.engine.ModularActionEngine.actionsForTrack(com.example.engine.ModularActionTrack.FACIAL)
        val hands = com.example.engine.ModularActionEngine.actionsForTrack(com.example.engine.ModularActionTrack.HANDS)
        val legs = com.example.engine.ModularActionEngine.actionsForTrack(com.example.engine.ModularActionTrack.LEGS)
        assertTrue("Expected 60+ Expressions, got ${exprs.size}", exprs.size >= 60)
        assertTrue("Expected 100+ Hand Parts, got ${hands.size}", hands.size >= 100)
        assertTrue("Expected 46+ Leg Parts, got ${legs.size}", legs.size >= 46)
        assertEquals("All 206 preset IDs must be unique", all.size, all.map { it.id }.toSet().size)
        listOf(
            "expr_idle_face", "expr_lipsing_with_hand", "expr_crying_emotional",
            "hand_idle_1", "hand_not_thinking", "hand_walk_with_gun", "hand_walk_with_thala", "hand_dancing_bhangra",
            "leg_namaskar", "leg_sitting_idle", "leg_running_fast", "leg_dancing_bhangra"
        ).forEach { id ->
            val def = com.example.engine.ModularActionEngine.findById(id)
            assertNotNull("Missing expanded preset: $id", def)
            val s0 = com.example.engine.ModularActionEngine.evaluateAtProgress(def!!, 0f)
            val s1 = com.example.engine.ModularActionEngine.evaluateAtProgress(def, 1f)
            assertEquals("Loop boundary mismatch on $id", s0.leftArmDelta, s1.leftArmDelta, 0.5f)
            assertEquals("Loop boundary mismatch on $id", s0.rightLegDelta, s1.rightLegDelta, 0.5f)
        }
    }

    @Test
    fun `BuiltInCatalog includes Pixel-Perfect Desi Story Cast with Raju Kisan featured and unique IDs`() {
        val chars = BuiltInCatalog.builtInCharacters
        assertEquals("All built-in character IDs must be unique", chars.size, chars.map { it.id }.toSet().size)

        val raju = chars.firstOrNull { it.id == "char_raju_kisan_pro" }
        assertNotNull("Raju Kisan must be present in BuiltInCatalog", raju)
        assertEquals("Raju Kisan", raju!!.name)
        assertEquals(HumanCostumeStyle.DESI_KURTA_DHOTI, raju.costumeStyle)
        assertEquals(CharacterAccessory.DESI_PAGDI_TILAK, raju.accessory)
        assertTrue("Raju Kisan should be pinned at top of Character List", raju.isPinned)

        listOf(
            "char_ramesh_seth_pro",
            "char_dadaji_pro",
            "char_meera_devi_pro",
            "char_mohan_pandit_pro",
            "char_chotu_boy_pro"
        ).forEach { expectedId ->
            assertNotNull("Missing new Desi character: $expectedId", chars.firstOrNull { it.id == expectedId })
        }
    }
}
