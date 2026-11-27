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
}

