package com.ai.assistance.operit.core.avatar.impl.mmd.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ai.assistance.mmd.MmdGlSurfaceView
import com.ai.assistance.operit.core.avatar.common.control.AvatarController
import com.ai.assistance.operit.core.avatar.impl.mmd.control.MmdAvatarController
import com.ai.assistance.operit.core.avatar.impl.mmd.control.MmdExpressionFrame
import com.ai.assistance.operit.core.avatar.impl.mmd.model.MmdAvatarModel

@Composable
fun MmdRenderer(
    modifier: Modifier,
    model: MmdAvatarModel,
    controller: AvatarController,
    onError: (String) -> Unit
) {
    val mmdController = controller as? MmdAvatarController
        ?: throw IllegalArgumentException("MmdRenderer requires a MmdAvatarController")

    val scale by mmdController.scale.collectAsState()
    val translateX by mmdController.translateX.collectAsState()
    val translateY by mmdController.translateY.collectAsState()
    val initialRotationX by mmdController.initialRotationX.collectAsState()
    val initialRotationY by mmdController.initialRotationY.collectAsState()
    val initialRotationZ by mmdController.initialRotationZ.collectAsState()
    val cameraDistanceScale by mmdController.cameraDistanceScale.collectAsState()
    val cameraTargetHeight by mmdController.cameraTargetHeight.collectAsState()
    val avatarState by mmdController.state.collectAsState()
    val expressionFrame by mmdController.expressionFrame.collectAsState()

    val safeScale = scale.coerceIn(0.2f, 5.0f)

    val lifecycleOwner = LocalLifecycleOwner.current
    val surfaceViewState = remember { mutableStateOf<MmdGlSurfaceView?>(null) }
    val renderErrorState = remember(model.modelPath) { mutableStateOf<String?>(null) }

    LaunchedEffect(model.modelPath) {
        renderErrorState.value = null
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surfaceViewState.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> surfaceViewState.value?.onPause()
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            surfaceViewState.value?.onPause()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .scale(safeScale)
            .offset(x = translateX.dp, y = translateY.dp)
            .background(Color.Transparent)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MmdGlSurfaceView(context).apply {
                    surfaceViewState.value = this
                    setOnRenderErrorListener { message ->
                        val normalized = message.takeIf { it.isNotBlank() }
                        renderErrorState.value = normalized
                        if (normalized != null) {
                            onError(normalized)
                        }
                    }
                    setModelPath(model.modelPath)
                    setAnimationState(avatarState.currentAnimation, avatarState.isLooping)
                    setModelRotation(initialRotationX, initialRotationY, initialRotationZ)
                    setCameraDistanceScale(cameraDistanceScale)
                    setCameraTargetHeight(cameraTargetHeight)
                    setAutoGlance(true)
                    // Operit patch: discover morphs once the model is loaded
                    requestMorphNames { names ->
                        mmdController.onMorphCatalogAvailable(names.toList())
                    }
                    onResume()
                }
            },
            update = { view ->
                surfaceViewState.value = view
                view.setModelPath(model.modelPath)
                view.setAnimationState(avatarState.currentAnimation, avatarState.isLooping)
                view.setModelRotation(initialRotationX, initialRotationY, initialRotationZ)
                view.setCameraDistanceScale(cameraDistanceScale)
                view.setCameraTargetHeight(cameraTargetHeight)
                // Operit patch: push the current expression frame to the native renderer
                val frame = expressionFrame
                if (frame.morphWeights.isEmpty()) {
                    view.clearMorphOverrides()
                } else {
                    view.setMorphWeights(
                        frame.morphWeights.keys.toTypedArray(),
                        frame.morphWeights.values.toFloatArray()
                    )
                }
                val headBone = mmdController.resolvedHeadBone()
                if (headBone != null && frame.headYawDeg != 0f) {
                    // NOTE: ApplyLookAtOverride() in C++ runs after node rotation overrides and
                    // overwrites the head bone, so driving the head through setNodeRotation()
                    // would be silently cancelled. Route head tracking through the native
                    // look-at channel instead; it already provides smoothing and a wander gaze
                    // when the target returns to zero.
                    view.setLookAt(
                        (frame.headYawDeg / 28f).coerceIn(-1f, 1f),
                        (frame.headPitchDeg / 18f).coerceIn(-1f, 1f)
                    )
                } else {
                    view.setLookAt(0f, 0f)
                }
            }
        )

        renderErrorState.value?.let { error ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}