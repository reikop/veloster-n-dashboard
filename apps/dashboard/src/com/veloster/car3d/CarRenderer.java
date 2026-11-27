package com.veloster.car3d;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * GLES 2.0 drawing of the 내 차 screen: a studio floor (soft shadow, headlamp / lamp light pools), the car mesh with
 * per-vertex colours and a fake studio reflection, the lamp atlas lit per lamp group, glass, and additive halos.
 * Fragment shaders stay in mediump (the tcc893x's Mali-class GPU has no highp in fragments).
 */
final class CarRenderer implements GLSurfaceView.Renderer {
    /** Camera, set from the UI thread. */
    static final class Camera { float yaw = 35, pitch = 5, dist = 6.0f; }

    private final Object lock = new Object();
    private final Camera camera = new Camera();
    private final float[] glow = new float[CarState.GROUPS];
    /** Swing of each hinged part, degrees (CarState.hingeDeg); hinge points from the mesh. */
    private final float[] hinge = new float[CarState.OPENINGS];
    private final float[][] hingeAt = new float[CarModel.HINGES][3];
    private final float[] hingeM = new float[16 * CarState.OPENINGS];
    private float wheelDeg, rollDeg, blur, bodyPitch, bodyRoll, distance;
    private int scene = CarScene.STUDIO;
    /** Backdrop panorama (sharp, behind the car) and a small blurred copy for the reflections; uploaded on the GL thread. */
    private Bitmap skyBmp, envBmp;
    private boolean skyUploaded;
    private int texSky, texEnv;
    private final float[] bodyM = new float[16], spot = new float[4], spotW = new float[4], spotN = new float[4];
    private final float[] paint = {0.20f, 0.44f, 0.71f};
    private boolean paintOn;
    private float paintGloss = 1f;

    private CarModel model;
    private Bitmap lampBase, lampGlow;
    private int[] vbo, ibo;
    private int texBase, texGlow;
    private boolean uploaded;
    private Program body, lamp, glass, floor, halo, rim, sky;
    private FloatBuffer quad;
    private int width = 800, height = 480;
    private final float[] proj = new float[16], view = new float[16], vp = new float[16], modelM = new float[16], mvp = new float[16], tmp = new float[16];
    private final float[] eye = new float[3];
    private static final float[] LIGHT = normalize(new float[] {0.45f, 0.8f, 0.4f});
    private static final float CLEAR_R = 0.035f, CLEAR_G = 0.04f, CLEAR_B = 0.055f;
    /** Halo colour per lamp group. */
    private static final float[][] HALO = {{0, 0, 0}, {1f, 0.97f, 0.9f}, {1f, 0.9f, 0.75f}, {1f, 0.08f, 0.05f}, {1f, 0.05f, 0.04f},
        {1f, 0.55f, 0.05f}, {1f, 0.55f, 0.05f}, {1f, 1f, 1f}, {0, 0, 0}};
    private static final float[] HALO_GAIN = {0, 0.75f, 0.25f, 0.45f, 0.8f, 0.9f, 0.9f, 0.35f, 0};

    // ---- state from the UI thread -----------------------------------------------------------------------------
    void setModel(CarModel m, Bitmap base, Bitmap glowMap) {
        synchronized (lock) {
            model = m; lampBase = base; lampGlow = glowMap; uploaded = false;
            for (CarModel.Chunk c : m.chunks) if (c.motion == CarModel.HINGE) { hingeAt[c.corner][0] = c.px; hingeAt[c.corner][1] = c.py; hingeAt[c.corner][2] = c.pz; }
        }
    }
    void setState(CarState s) {
        synchronized (lock) {
            System.arraycopy(s.glow, 0, glow, 0, glow.length); wheelDeg = s.wheelDeg; rollDeg = s.rollDeg; blur = s.blur; bodyPitch = s.pitchDeg; bodyRoll = s.rollBodyDeg; distance = s.distanceM;
            for (int h = 0; h < CarState.OPENINGS; h++) hinge[h] = s.hingeDeg(h);
        }
    }
    void setScene(int s) { synchronized (lock) { scene = CarScene.valid(s); } }
    void setBackdrop(Bitmap sharp, Bitmap blurred) { synchronized (lock) { skyBmp = sharp; envBmp = blurred; skyUploaded = false; } }
    void setCamera(float yaw, float pitch, float dist) { synchronized (lock) { camera.yaw = yaw; camera.pitch = pitch; camera.dist = dist; } }
    /** Body colour (ARGB), or 0 for the model's own Performance Blue; matte paints lose the clear-coat reflection. */
    void setPaint(int argb, boolean matte) {
        synchronized (lock) {
            paintOn = argb != 0; paintGloss = matte ? 0.12f : 1f;
            // Screen sRGB-ish swatch -> a little darker base colour so the studio reflection does not wash it out.
            paint[0] = ((argb >> 16) & 255) / 255f * 0.82f; paint[1] = ((argb >> 8) & 255) / 255f * 0.82f; paint[2] = (argb & 255) / 255f * 0.82f;
        }
    }

    // ---- GL thread -----------------------------------------------------------------------------------------------
    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        synchronized (lock) { uploaded = false; skyUploaded = false; texSky = texEnv = 0; }   // a new context: every buffer and texture is gone
        body = new Program(BODY_VS, BODY_FS); lamp = new Program(LAMP_VS, LAMP_FS); glass = new Program(BODY_VS, GLASS_FS);
        floor = new Program(FLOOR_VS, FLOOR_FS); halo = new Program(HALO_VS, HALO_FS); rim = new Program(RIM_VS, RIM_FS); sky = new Program(SKY_VS, SKY_FS);
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}); rewind(quad);
        GLES20.glClearColor(CLEAR_R, CLEAR_G, CLEAR_B, 1);
    }

    @Override public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = Math.max(1, w); height = Math.max(1, h);
        GLES20.glViewport(0, 0, width, height);
        Matrix.perspectiveM(proj, 0, 36f, width / (float) height, 0.3f, 60f);
    }

    @Override public void onDrawFrame(GL10 unused) {
        float yaw, pitch, dist, wd, roll, pGloss, bl, road; int sc; boolean pOn; float[] g = new float[CarState.GROUPS]; float[] pc = new float[3];
        CarModel m;
        synchronized (lock) {
            yaw = camera.yaw; pitch = camera.pitch; dist = camera.dist; wd = wheelDeg; roll = rollDeg; bl = blur; road = distance; sc = scene;
            bodyMatrix(bodyPitch, bodyRoll); hingeMatrices(); pOn = paintOn; pGloss = paintGloss;
            System.arraycopy(glow, 0, g, 0, g.length); System.arraycopy(paint, 0, pc, 0, 3);
            m = model;
            if (m != null && !uploaded) { upload(m, lampBase, lampGlow); uploaded = true; }
            if (skyBmp != null && !skyUploaded) {
                if (texSky != 0) GLES20.glDeleteTextures(2, new int[] {texSky, texEnv}, 0);
                texSky = panorama(skyBmp); texEnv = panorama(envBmp); skyUploaded = true;
            }
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        double cy = Math.toRadians(yaw), cp = Math.toRadians(pitch);
        float tx = 0, ty = 0.58f, tz = -0.1f;
        eye[0] = tx + (float) (Math.sin(cy) * Math.cos(cp) * dist);
        eye[1] = ty + (float) (Math.sin(cp) * dist);
        eye[2] = tz + (float) (Math.cos(cy) * Math.cos(cp) * dist);
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], tx, ty, tz, 0, 1, 0);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);

        GLES20.glDisable(GLES20.GL_CULL_FACE);   // the model has double-sided parts; shaders flip back faces
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texEnv);   // reflections, every program
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texSky);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        drawSky();
        drawFloor(g, sc, road);
        if (m == null) return;
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
        // Opaque body, then lamps.
        body.use();
        GLES20.glUniform3f(body.u("uEye"), eye[0], eye[1], eye[2]);
        env(body, sc);
        GLES20.glUniform3f(body.u("uPaint"), pc[0], pc[1], pc[2]);
        GLES20.glUniform1f(body.u("uPaintOn"), pOn ? 1f : 0f);
        GLES20.glUniform1f(body.u("uPaintGloss"), pGloss);
        for (int i = 0; i < m.chunks.length; i++) if (m.chunks[i].program == CarModel.BODY) drawChunk(body, m, i, wd, roll);
        lamp.use();
        GLES20.glUniform3f(lamp.u("uEye"), eye[0], eye[1], eye[2]);
        env(lamp, sc);
        GLES20.glUniform1fv(lamp.u("uGlow"), CarState.GROUPS, g, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBase);
        GLES20.glUniform1i(lamp.u("uBase"), 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texGlow);
        GLES20.glUniform1i(lamp.u("uGlowTex"), 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        for (int i = 0; i < m.chunks.length; i++) if (m.chunks[i].program == CarModel.LAMP) drawChunk(lamp, m, i, wd, roll);
        // Glass: premultiplied blending, no depth writes.
        GLES20.glEnable(GLES20.GL_BLEND);
        if (bl > 0.01f) drawBlur(m, wd, bl);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        glass.use();
        GLES20.glUniform3f(glass.u("uEye"), eye[0], eye[1], eye[2]);
        env(glass, sc);
        GLES20.glUniform3f(glass.u("uPaint"), pc[0], pc[1], pc[2]);
        GLES20.glUniform1f(glass.u("uPaintOn"), 0f);
        GLES20.glUniform1f(glass.u("uPaintGloss"), 1f);
        for (int i = 0; i < m.chunks.length; i++) if (m.chunks[i].program == CarModel.GLASS) drawChunk(glass, m, i, wd, roll);
        drawHalos(m, g, sc);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void upload(CarModel m, Bitmap base, Bitmap glowMap) {
        int n = m.chunks.length;
        vbo = new int[n]; ibo = new int[n];
        GLES20.glGenBuffers(n, vbo, 0); GLES20.glGenBuffers(n, ibo, 0);
        for (int i = 0; i < n; i++) {
            CarModel.Chunk c = m.chunks[i];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[i]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, c.vertices * CarModel.STRIDE, rewind(c.vertexData), GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[i]);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, c.indices * 2, rewind(c.indexData), GLES20.GL_STATIC_DRAW);
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0); GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        texBase = texture(base); texGlow = texture(glowMap);
    }

    private static int texture(Bitmap b) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        if (b != null) { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0); GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D); }
        else {
            ByteBuffer px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()); px.put(new byte[] {0, 0, 0, (byte) 255}); rewind(px);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        }
        return t[0];
    }

    /** A panorama: wraps around horizontally, clamps at the top and bottom, no mipmaps (no seam where u wraps). */
    private static int panorama(Bitmap b) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        return t[0];
    }

    /** Body on its springs: pitch / roll about a point between the axles at hub height; the wheels do not follow. */
    private void bodyMatrix(float pitch, float roll) {
        Matrix.setIdentityM(bodyM, 0);
        Matrix.translateM(bodyM, 0, 0, 0.42f, -0.075f);
        Matrix.rotateM(bodyM, 0, pitch, 1, 0, 0);
        Matrix.rotateM(bodyM, 0, roll, 0, 0, 1);
        Matrix.translateM(bodyM, 0, 0, -0.42f, 0.075f);
    }

    /** Per hinged part: the body matrix (or the door a mirror sits on), then the swing about its hinge (the part's vertices
     *  are relative to the hinge). Parents come first in the HINGES order. */
    private void hingeMatrices() {
        for (int h = 0; h < CarState.OPENINGS; h++) {
            int o = h * 16, parent = CarState.HINGE_PARENT[h];
            if (parent < 0) {
                System.arraycopy(bodyM, 0, hingeM, o, 16);
                Matrix.translateM(hingeM, o, hingeAt[h][0], hingeAt[h][1], hingeAt[h][2]);
            } else {   // the parent's matrix is relative to its own hinge
                System.arraycopy(hingeM, parent * 16, hingeM, o, 16);
                Matrix.translateM(hingeM, o, hingeAt[h][0] - hingeAt[parent][0], hingeAt[h][1] - hingeAt[parent][1], hingeAt[h][2] - hingeAt[parent][2]);
            }
            if (hinge[h] > 0.01f) Matrix.rotateM(hingeM, o, hinge[h], CarState.HINGE_AXIS[h][0], CarState.HINGE_AXIS[h][1], CarState.HINGE_AXIS[h][2]);
        }
    }

    private void drawChunk(Program p, CarModel m, int i, float wheel, float roll) {
        CarModel.Chunk c = m.chunks[i];
        if (c.motion == CarModel.HINGE && c.corner == CarModel.ENGINE && hinge[CarModel.BONNET] < 0.5f) return;   // the bay only shows under an open bonnet
        if (c.motion == CarModel.HINGE && c.corner < CarState.OPENINGS) System.arraycopy(hingeM, c.corner * 16, modelM, 0, 16);
        else if (c.motion == CarModel.FIXED || c.motion == CarModel.HINGE) System.arraycopy(bodyM, 0, modelM, 0, 16);
        else Matrix.setIdentityM(modelM, 0);
        if (c.motion == CarModel.WHEEL || c.motion == CarModel.CALLIPER) {
            Matrix.translateM(modelM, 0, c.px, c.py, c.pz);
            if (c.corner == CarModel.FL || c.corner == CarModel.FR) Matrix.rotateM(modelM, 0, wheel, 0, 1, 0);
            if (c.motion == CarModel.WHEEL) Matrix.rotateM(modelM, 0, roll, 1, 0, 0);
        }
        Matrix.multiplyMM(mvp, 0, vp, 0, modelM, 0);
        GLES20.glUniformMatrix4fv(p.u("uMVP"), 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(p.u("uModel"), 1, false, modelM, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[i]);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[i]);
        p.attrib("aPos", 3, GLES20.GL_FLOAT, false, 0);
        p.attrib("aNrm", 3, GLES20.GL_SHORT, true, 12);   // 16-bit normals: 8-bit ones ripple in the paint reflection
        p.attrib("aGroup", 1, GLES20.GL_UNSIGNED_SHORT, false, 18);
        p.attrib("aCol", 4, GLES20.GL_UNSIGNED_BYTE, true, 20);
        p.attrib("aUV", 2, GLES20.GL_UNSIGNED_SHORT, true, 24);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, c.indices, GLES20.GL_UNSIGNED_SHORT, 0);
        p.disableAttribs();
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /** Key light, ambient and the reflected backdrop (texture unit 2). */
    private static void env(Program p, int sc) {
        GLES20.glUniform3f(p.u("uLight"), LIGHT[0], LIGHT[1], LIGHT[2]);
        v3(p, "uKey", CarScene.KEY[sc]); GLES20.glUniform1f(p.u("uAmbient"), CarScene.AMBIENT[sc]);
        GLES20.glUniform1i(p.u("uEnv"), 2); GLES20.glUniform1f(p.u("uEnvGain"), CarScene.ENV_GAIN[sc]);
    }

    private static void v3(Program p, String name, float[] c) { GLES20.glUniform3f(p.u(name), c[0], c[1], c[2]); }

    private final float[] invVP = new float[16];

    /** The backdrop panorama over the whole screen: each pixel looks it up by its view direction (texture unit 3). */
    private void drawSky() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        if (!Matrix.invertM(invVP, 0, vp, 0)) return;
        sky.use();
        GLES20.glUniformMatrix4fv(sky.u("uInvVP"), 1, false, invVP, 0);
        GLES20.glUniform3f(sky.u("uEye"), eye[0], eye[1], eye[2]);
        GLES20.glUniform1i(sky.u("uSky"), 3);
        quadDraw(sky);
    }

    private void drawFloor(float[] g, int sc, float road) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);   // the floor fades out into the panorama's own ground far away
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        floor.use();
        GLES20.glUniform3f(floor.u("uEye"), eye[0], eye[1], eye[2]);
        GLES20.glUniform1i(floor.u("uEnv"), 2);
        GLES20.glUniform1f(floor.u("uRefl"), CarScene.FLOOR_REFLECT[sc]);
        GLES20.glUniformMatrix4fv(floor.u("uMVP"), 1, false, vp, 0);
        GLES20.glUniform1f(floor.u("uSize"), 30f);
        v3(floor, "uFloor", CarScene.FLOOR[sc]);
        GLES20.glUniform1f(floor.u("uScene"), sc);
        GLES20.glUniform1f(floor.u("uRoad"), road);
        GLES20.glUniform4f(floor.u("uLamps"), g[CarState.HEAD], Math.max(g[CarState.TAIL], g[CarState.STOP]), g[CarState.REVERSE], g[CarState.POSITION]);
        GLES20.glUniform2f(floor.u("uTurn"), g[CarState.TURN_L], g[CarState.TURN_R]);
        quadDraw(floor);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /** A spun-metal disc over each rim's outer face, fading in with speed; the callipers show through the spokes. */
    private void drawBlur(CarModel m, float wheel, float amount) {
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        rim.use();
        GLES20.glUniform1f(rim.u("uR"), RIM_RADIUS);
        GLES20.glUniform1f(rim.u("uAlpha"), amount);
        for (CarModel.Chunk c : m.chunks) {
            if (c.motion != CarModel.WHEEL) continue;
            boolean front = c.corner == CarModel.FL || c.corner == CarModel.FR;
            float side = c.corner == CarModel.FL || c.corner == CarModel.RL ? 1f : -1f;   // outer face: +X on the left
            float a = front ? (float) Math.toRadians(wheel) : 0f;
            Matrix.setIdentityM(modelM, 0);
            Matrix.translateM(modelM, 0, c.px, c.py, c.pz);
            if (front) Matrix.rotateM(modelM, 0, wheel, 0, 1, 0);
            Matrix.multiplyMM(mvp, 0, vp, 0, modelM, 0);
            GLES20.glUniformMatrix4fv(rim.u("uMVP"), 1, false, mvp, 0);
            GLES20.glUniform1f(rim.u("uX"), side * RIM_FACE_X);
            float ax = side * (float) Math.cos(a), az = -side * (float) Math.sin(a);   // the axle in world space
            GLES20.glUniform1f(rim.u("uShade"), 0.55f + 0.7f * Math.max(0f, ax * LIGHT[0] + az * LIGHT[2]));
            quadDraw(rim);
        }
    }

    private void drawHalos(CarModel m, float[] g, int sc) {
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        halo.use();
        GLES20.glUniformMatrix4fv(halo.u("uVP"), 1, false, vp, 0);
        // Camera right / up in world space from the view matrix rows.
        GLES20.glUniform3f(halo.u("uRight"), view[0], view[4], view[8]);
        float refl = CarScene.FLOOR_REFLECT[sc];
        for (int pass = refl > 0.2f ? 1 : 0; pass >= 0; pass--)   // pass 1: the lamps mirrored in a shiny / wet floor, stretched
        for (CarModel.Spot s : m.spots) {
            float stretch = pass == 1 ? 2.6f : 1f;
            GLES20.glUniform3f(halo.u("uUp"), view[1] * stretch, view[5] * stretch, view[9] * stretch);
            float k = s.group < g.length ? g[s.group] * HALO_GAIN[s.group] : 0;
            if (k < 0.02f) continue;
            float nx = s.nx, ny = s.ny, nz = s.nz;
            if (s.hinge >= 0 && s.hinge < CarState.OPENINGS) {   // a lamp on a door / hatch / mirror swings with it
                spot[0] = s.x - hingeAt[s.hinge][0]; spot[1] = s.y - hingeAt[s.hinge][1]; spot[2] = s.z - hingeAt[s.hinge][2]; spot[3] = 1;
                Matrix.multiplyMV(spotW, 0, hingeM, s.hinge * 16, spot, 0);
                spot[0] = nx; spot[1] = ny; spot[2] = nz; spot[3] = 0;
                Matrix.multiplyMV(spotN, 0, hingeM, s.hinge * 16, spot, 0); nx = spotN[0]; ny = spotN[1]; nz = spotN[2];
            } else {
                spot[0] = s.x; spot[1] = s.y; spot[2] = s.z; spot[3] = 1;
                Matrix.multiplyMV(spotW, 0, bodyM, 0, spot, 0);   // the lamps ride on the body
            }
            float sx = spotW[0], sy = pass == 1 ? -spotW[1] : spotW[1], sz = spotW[2];
            float vx = eye[0] - sx, vy = eye[1] - sy, vz = eye[2] - sz, vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
            float facing = (vx * nx + vy * ny + vz * nz) / vl;   // halos only towards where the lamp shines
            facing = Math.max(0f, Math.min(1f, facing * 1.6f + 0.15f));
            if (facing <= 0.01f) continue;
            float size = s.radius * 1.2f + 0.07f, push = 0.12f;
            GLES20.glUniform3f(halo.u("uCentre"), sx + vx / vl * push, sy + vy / vl * push, sz + vz / vl * push);
            GLES20.glUniform1f(halo.u("uSize"), size * (0.7f + 0.3f * k));
            float[] c = HALO[s.group];
            float f = k * facing * (pass == 1 ? refl * 0.8f : 1f);
            GLES20.glUniform3f(halo.u("uColor"), c[0] * f, c[1] * f, c[2] * f);
            quadDraw(halo);
        }
    }

    private void quadDraw(Program p) {
        int a = p.a("aCorner");
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glEnableVertexAttribArray(a);
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, rewind(quad));
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(a);
    }

    /** Buffer.position(int) through the Buffer type: JDK 9+ ByteBuffer.position returns ByteBuffer, absent on API 17. */
    static java.nio.Buffer rewind(java.nio.Buffer b) { return b.position(0); }

    private static float[] normalize(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new float[] {v[0] / l, v[1] / l, v[2] / l};
    }

    // ---- shaders ---------------------------------------------------------------------------------------------------
    private static final String BODY_VS =
        "uniform mat4 uMVP; uniform mat4 uModel; uniform vec3 uPaint; uniform float uPaintOn; uniform float uPaintGloss;\n" +
        "attribute vec3 aPos; attribute vec3 aNrm; attribute float aGroup; attribute vec4 aCol;\n" +
        "varying vec3 vN; varying vec3 vW; varying vec4 vC;\n" +
        "void main(){ vW=(uModel*vec4(aPos,1.0)).xyz; vN=(uModel*vec4(aNrm,0.0)).xyz;\n" +
        " float paint=step(7.5,aGroup); vC=aCol; vC.rgb=mix(aCol.rgb,uPaint,uPaintOn*paint); vC.a=mix(aCol.a,aCol.a*uPaintGloss,paint);\n" +
        " gl_Position=uMVP*vec4(aPos,1.0); }\n";
    /** Panorama lookup by direction: u = azimuth (atan2(x, z) / 2pi + 0.5), v = elevation from +64 (top) to -16 deg. */
    private static final String ENV_LOOKUP =
        "vec3 env(sampler2D t, vec3 d){ float u=atan(d.x,d.z)*0.159155+0.5; float e=asin(clamp(d.y,-1.0,1.0))*57.29578;\n" +
        " return texture2D(t,vec2(u,clamp((64.0-e)/80.0,0.004,0.996))).rgb; }\n";
    /** Shared lighting: hemisphere + key light, the backdrop reflected with Fresnel. */
    private static final String LIGHTING =
        "uniform vec3 uEye; uniform vec3 uLight; uniform vec3 uKey; uniform float uAmbient; uniform sampler2D uEnv; uniform float uEnvGain;\n" +
        ENV_LOOKUP +
        "vec3 shade(vec3 base, float gloss, vec3 n, vec3 v){\n" +
        " float ndv=dot(n,v); if(ndv<0.0){ n=-n; ndv=-ndv; }\n" +
        " float diff=max(dot(n,uLight),0.0); float hemi=0.5+0.5*n.y;\n" +
        " vec3 r=reflect(-v,n);\n" +
        " vec3 sky=env(uEnv,r)*uEnvGain;\n" +
        " float fres=0.06+0.94*pow(1.0-ndv,5.0);\n" +
        " vec3 h=normalize(uLight+v); float spec=pow(max(dot(n,h),0.0),70.0);\n" +
        " vec3 lit=base*((0.14+0.32*hemi)*uAmbient+0.78*diff*uKey);\n" +
        " return lit*(1.0-gloss*0.2)+gloss*(sky*mix(0.12,1.0,fres)*0.8+uKey*spec*1.2);\n" +
        "}\n";
    private static final String BODY_FS =
        "precision mediump float;\n" + LIGHTING +
        "varying vec3 vN; varying vec3 vW; varying vec4 vC;\n" +
        "void main(){ vec3 n=normalize(vN); vec3 v=normalize(uEye-vW);\n" +
        " gl_FragColor=vec4(shade(vC.rgb,vC.a,n,v),1.0); }\n";
    private static final String GLASS_FS =
        "precision mediump float;\n" + LIGHTING +
        "varying vec3 vN; varying vec3 vW; varying vec4 vC;\n" +
        "void main(){ vec3 n=normalize(vN); vec3 v=normalize(uEye-vW);\n" +
        " float ndv=abs(dot(n,v)); float fres=0.05+0.95*pow(1.0-ndv,5.0);\n" +
        " vec3 refl=shade(vec3(0.0),1.0,n,v);\n" +
        " float a=clamp(vC.a+fres*0.35,0.0,1.0);\n" +
        " gl_FragColor=vec4(vC.rgb*vC.a+refl*0.6,a); }\n";
    private static final String LAMP_VS =
        "uniform mat4 uMVP; uniform mat4 uModel; uniform float uGlow[9];\n" +
        "attribute vec3 aPos; attribute vec3 aNrm; attribute float aGroup; attribute vec4 aCol; attribute vec2 aUV;\n" +
        "varying vec3 vN; varying vec3 vW; varying vec2 vUV; varying float vK;\n" +
        "void main(){ vW=(uModel*vec4(aPos,1.0)).xyz; vN=(uModel*vec4(aNrm,0.0)).xyz; vUV=aUV;\n" +
        " vK=uGlow[int(aGroup+0.5)]*step(0.5,aGroup);\n" +
        " gl_Position=uMVP*vec4(aPos,1.0); }\n";
    private static final String LAMP_FS =
        "precision mediump float;\n" + LIGHTING +
        "uniform sampler2D uBase; uniform sampler2D uGlowTex;\n" +
        "varying vec3 vN; varying vec3 vW; varying vec2 vUV; varying float vK;\n" +
        "void main(){ vec3 n=normalize(vN); vec3 v=normalize(uEye-vW);\n" +
        " vec3 b=texture2D(uBase,vUV).rgb; vec3 e=texture2D(uGlowTex,vUV).rgb;\n" +
        " vec3 c=shade(b*0.75,0.5,n,v)*(1.0-0.5*vK)+e*(0.05+vK*1.9);\n" +
        " gl_FragColor=vec4(c,1.0); }\n";
    private static final String FLOOR_VS =
        "uniform mat4 uMVP; uniform float uSize; attribute vec2 aCorner; varying vec2 vXZ;\n" +
        "void main(){ vXZ=aCorner*uSize; gl_Position=uMVP*vec4(vXZ.x,0.0,vXZ.y,1.0); }\n";
    private static final String FLOOR_FS =
        "precision mediump float;\n" +
        "uniform vec3 uFloor; uniform float uScene; uniform float uRoad; uniform vec4 uLamps; uniform vec2 uTurn;\n" +
        "uniform vec3 uEye; uniform sampler2D uEnv; uniform float uRefl; varying vec2 vXZ;\n" + ENV_LOOKUP +
        "float pool(vec2 p, vec2 at, float r){ return exp(-dot(p-at,p-at)/(r*r)); }\n" +
        "float line(float x, float at, float w){ return 1.0-smoothstep(w*0.5,w*0.5+0.02,abs(x-at)); }\n" +
        "void main(){ vec2 p=vXZ; float d=length(p*vec2(1.0,0.75));\n" +
        " vec3 f=uFloor; float refl=uRefl;\n" +
        " if(uScene<0.5){ float ring=smoothstep(3.55,3.6,d)*(1.0-smoothstep(3.6,3.68,d)); f+=vec3(0.05,0.07,0.1)*ring; }\n" +   // studio: turntable ring
        " else if(uScene<1.5){ f*=0.85+0.15*fract(sin(dot(floor(p*3.0),vec2(12.9898,78.233)))*43758.5453); }\n" +                 // sunset: worn asphalt
        " else if(uScene<2.5){ vec2 q=abs(fract(p*0.5)-0.5); f*=1.0-0.2*(1.0-smoothstep(0.485,0.495,max(q.x,q.y)));\n" +         // garage: concrete slabs
        "   float bay=(line(p.x,1.45,0.1)+line(p.x,-1.45,0.1))*step(abs(p.y),2.8); f=mix(f,vec3(0.85,0.7,0.1),0.8*bay); refl*=1.0-bay; }\n" +
        " else { float dash=step(0.55,fract((p.y+uRoad)/6.0));\n" +                                                             // night road: lane marks scroll with speed
        "   float paint=0.85*dash*(line(p.x,1.85,0.12)+line(p.x,-1.85,0.12))+0.8*(line(p.x,5.6,0.15)+line(p.x,-5.6,0.15));\n" +
        "   f=mix(f,vec3(0.7,0.7,0.66),paint); refl*=(1.0-0.7*paint)*(0.75+0.5*fract(sin(dot(floor(p*1.7),vec2(7.1,3.7)))*9631.7)); }\n" +   // puddles
        " vec3 vd=normalize(vec3(p.x,0.0,p.y)-uEye);\n" +
        " float fres=0.25+0.75*pow(1.0-abs(vd.y),3.0);\n" +
        " float sh=length(vec2(p.x/1.0,(p.y+0.08)/2.15)); float shadow=mix(0.18,1.0,smoothstep(0.55,1.25,sh));\n" +
        " vec3 c=(f+env(uEnv,vec3(vd.x,-vd.y,vd.z))*refl*fres)*shadow;\n" +
        " float alpha=1.0-smoothstep(uScene>2.5?10.0:6.0,uScene>2.5?28.0:20.0,d);\n" +
        " float z=p.y-1.95;\n" +                                                     // headlamp beams ahead of the car
        " float beam=0.0; if(z>0.0){ float w=0.35+z*0.32; beam=(exp(-pow((p.x-0.66)/w,2.0))+exp(-pow((p.x+0.66)/w,2.0)))*exp(-z*0.28)*smoothstep(0.0,0.7,z); }\n" +
        " c+=vec3(1.0,0.96,0.86)*beam*0.42*uLamps.x;\n" +
        " c+=vec3(1.0,0.85,0.6)*pool(p,vec2(0.0,2.3),0.9)*0.05*uLamps.w;\n" +
        " c+=vec3(1.0,0.06,0.04)*(pool(p,vec2(0.55,-2.25),0.75)+pool(p,vec2(-0.55,-2.25),0.75))*0.16*uLamps.y;\n" +
        " c+=vec3(0.9,0.95,1.0)*pool(p,vec2(0.0,-2.9),1.1)*0.28*uLamps.z;\n" +
        " vec3 amber=vec3(1.0,0.5,0.04);\n" +
        " c+=amber*0.22*uTurn.x*(pool(p,vec2(0.85,2.05),0.6)+pool(p,vec2(0.75,-2.05),0.6)+pool(p,vec2(1.25,0.55),0.45));\n" +
        " c+=amber*0.22*uTurn.y*(pool(p,vec2(-0.85,2.05),0.6)+pool(p,vec2(-0.75,-2.05),0.6)+pool(p,vec2(-1.25,0.55),0.45));\n" +
        " gl_FragColor=vec4(c,alpha); }\n";
    private static final String SKY_VS =
        "uniform mat4 uInvVP; uniform vec3 uEye; attribute vec2 aCorner; varying vec3 vDir;\n" +
        "void main(){ vec4 q=uInvVP*vec4(aCorner,1.0,1.0); vDir=q.xyz/q.w-uEye; gl_Position=vec4(aCorner,0.999,1.0); }\n";
    private static final String SKY_FS =
        "precision mediump float;\n" + ENV_LOOKUP +
        "uniform sampler2D uSky; varying vec3 vDir;\n" +
        "void main(){ gl_FragColor=vec4(env(uSky,normalize(vDir)),1.0); }\n";
    /** 19-inch rim (0.241 m) plus its lip; the rim's outer face sits just inside the tyre wall (0.123 m from the hub). */
    private static final float RIM_RADIUS = 0.247f, RIM_FACE_X = 0.121f;
    private static final String RIM_VS =
        "uniform mat4 uMVP; uniform float uX; uniform float uR; attribute vec2 aCorner; varying vec2 vUV;\n" +
        "void main(){ vUV=aCorner; gl_Position=uMVP*vec4(uX,aCorner.y*uR,aCorner.x*uR,1.0); }\n";
    private static final String RIM_FS =
        "precision mediump float;\n" +
        "uniform float uAlpha; uniform float uShade; varying vec2 vUV;\n" +
        "void main(){ float r=length(vUV);\n" +
        " float spokes=smoothstep(0.2,0.26,r)*(1.0-smoothstep(0.84,0.9,r));\n" +
        " float c=mix(0.16,0.30+0.035*sin(r*70.0),spokes);\n" +                    // hub, then spokes smeared into rings
        " c=mix(c,0.46,smoothstep(0.86,0.92,r));\n" +                               // the bright rim lip
        " c=mix(0.08,c,smoothstep(0.08,0.12,r));\n" +                               // centre cap
        " float a=uAlpha*mix(1.0,0.72,spokes)*(1.0-smoothstep(0.95,1.0,r));\n" +    // callipers show through the spoke ring
        " gl_FragColor=vec4(vec3(c*uShade),a); }\n";
    private static final String HALO_VS =
        "uniform mat4 uVP; uniform vec3 uCentre; uniform vec3 uRight; uniform vec3 uUp; uniform float uSize;\n" +
        "attribute vec2 aCorner; varying vec2 vUV;\n" +
        "void main(){ vUV=aCorner; vec3 w=uCentre+(uRight*aCorner.x+uUp*aCorner.y)*uSize; gl_Position=uVP*vec4(w,1.0); }\n";
    private static final String HALO_FS =
        "precision mediump float;\n" +
        "uniform vec3 uColor; varying vec2 vUV;\n" +
        "void main(){ float r=dot(vUV,vUV); float k=exp(-r*5.0)+0.6*exp(-r*40.0); gl_FragColor=vec4(uColor*k*(1.0-smoothstep(0.8,1.0,r)),1.0); }\n";

    /** A linked program with cached locations. */
    private static final class Program {
        final int id;
        private final java.util.HashMap<String, Integer> loc = new java.util.HashMap<String, Integer>();
        private final java.util.ArrayList<Integer> enabled = new java.util.ArrayList<Integer>();

        Program(String vs, String fs) {
            int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
            id = GLES20.glCreateProgram();
            GLES20.glAttachShader(id, v); GLES20.glAttachShader(id, f);
            GLES20.glLinkProgram(id);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("link: " + GLES20.glGetProgramInfoLog(id));
        }

        void use() { GLES20.glUseProgram(id); }

        int u(String name) {
            Integer l = loc.get(name);
            if (l == null) { l = GLES20.glGetUniformLocation(id, name); loc.put(name, l); }
            return l;
        }

        int a(String name) {
            Integer l = loc.get("@" + name);
            if (l == null) { l = GLES20.glGetAttribLocation(id, name); loc.put("@" + name, l); }
            return l;
        }

        void attrib(String name, int size, int type, boolean normalized, int offset) {
            int l = a(name);
            if (l < 0) return;   // optimised away
            GLES20.glEnableVertexAttribArray(l);
            GLES20.glVertexAttribPointer(l, size, type, normalized, CarModel.STRIDE, offset);
            enabled.add(l);
        }

        void disableAttribs() { for (int l : enabled) GLES20.glDisableVertexAttribArray(l); enabled.clear(); }

        private static int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }
    }

    /** RGB888 with 4x MSAA and a 24-bit depth buffer when the GPU offers it, else step down to RGB565 / 16-bit depth. */
    static final class ConfigChooser implements GLSurfaceView.EGLConfigChooser {
        private static final int EGL_OPENGL_ES2_BIT = 4;
        @Override public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            int[][] wants = {
                {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4},
                {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4},
                {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 24},
                {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16},
                {EGL10.EGL_RED_SIZE, 5, EGL10.EGL_GREEN_SIZE, 6, EGL10.EGL_BLUE_SIZE, 5, EGL10.EGL_DEPTH_SIZE, 16}};
            for (int[] want : wants) {
                int[] attrs = new int[want.length + 3];
                System.arraycopy(want, 0, attrs, 0, want.length);
                attrs[want.length] = EGL10.EGL_RENDERABLE_TYPE; attrs[want.length + 1] = EGL_OPENGL_ES2_BIT; attrs[want.length + 2] = EGL10.EGL_NONE;
                EGLConfig[] configs = new EGLConfig[1]; int[] n = new int[1];
                if (egl.eglChooseConfig(display, attrs, configs, 1, n) && n[0] > 0) {
                    android.util.Log.i("Car3D", "EGL config " + java.util.Arrays.toString(want));
                    return configs[0];
                }
            }
            throw new IllegalArgumentException("no GLES2 config");
        }
    }
}
