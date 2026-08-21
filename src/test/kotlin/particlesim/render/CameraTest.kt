package particlesim.render

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class CameraTest {

    @Test
    fun `fixed camera returns the same pose regardless of t or scene`() {
        val pose = CameraPose(position = Vector3(1.0, 2.0, 3.0), lookAt = Vector3.ZERO)
        val camera = CameraFunction.fixed(pose)
        val scene = SceneQueryImpl(ParticleStore(), Groups())

        assertEquals(pose, camera.evaluate(0.0, scene))
        assertEquals(pose, camera.evaluate(99.0, scene))
    }

    @Test
    fun `a scripted camera can orbit a group's centroid while looking at a specific particle`() {
        val store = ParticleStore()
        val groups = Groups()
        val tip = store.create(position = Vector3(5.0, 0.0, 0.0))
        val a = store.create(position = Vector3(-1.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0))
        groups.add("body", a)
        groups.add("body", b)
        val scene = SceneQueryImpl(store, groups)

        val camera = CameraFunction { t, s ->
            val center = s.centroid("body")
            CameraPose(
                position = center + Vector3(sin(t) * 5.0, 2.0, cos(t) * 5.0),
                lookAt = s.position(tip),
            )
        }

        val pose = camera.evaluate(0.0, scene)
        assertEquals(Vector3(0.0, 2.0, 5.0), pose.position) // centroid(body)=(0,0,0), sin(0)=0, cos(0)=1
        assertEquals(Vector3(5.0, 0.0, 0.0), pose.lookAt)
        assertEquals(Vector3(0.0, 1.0, 0.0), pose.up) // default
    }
}
