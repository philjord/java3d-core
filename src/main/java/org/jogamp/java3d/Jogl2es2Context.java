/* Copyright (c) 2016 JogAmp Community. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation. Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

package org.jogamp.java3d;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;

import org.jogamp.vecmath.Matrix3d;
import org.jogamp.vecmath.Matrix4d;
import org.jogamp.vecmath.Vector3f;
import org.jogamp.vecmath.Vector4f;

import com.jogamp.opengl.GL2ES2;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GLContext;


public class Jogl2es2Context extends JoglContext
{

	public Jogl2es2Context(GLContext context)
	{
		super(context);
	}
	
	public GL2ES2 gl2es2()
	{
		return context.getGL().getGL2ES2();
	}

	public GL2ES3 gl2es3()
	{
		if(context.getGL().isGL2ES3())
			return context.getGL().getGL2ES3();
		else
			return null;
	}

	public JoglShaderObject shaderProgram;
	public int shaderProgramId = -1;
	public ProgramData programData;

	@Override
	void setShaderProgram(JoglShaderObject object)
	{
		super.setShaderProgram(object);
		shaderProgram = object;
		shaderProgramId = object == null ? -1 : object.getValue();
		programData = allProgramData.get(shaderProgramId);
		if (programData == null)
		{
			programData = new ProgramData();
			allProgramData.put(shaderProgramId, programData);
		}

	}

	// all buffers created are recorded for each render pass, and for cleanup
	public ArrayList<GeometryArrayRetained> geoToClearBuffers = new ArrayList<GeometryArrayRetained>();

	public SparseArray<GeometryData> allGeometryData = new SparseArray<GeometryData>();

	public static class GeometryData
	{
		public int nativeId = -1;

		public int geoToIndBuf = -1;
		public int geoToIndBufSize = -1;
		public int[] geoToIndStripBuf = null;
		//public int geoToIndStripSwappedSize = -1;// removed into j3dnotristrips
		public int geoToCoordBuf = -1;
		public int geoToCoordBuf1 = -2;// double buffered for updates
		public int geoToCoordBuf2 = -3;
		public int geoToCoordBuf3 = -3;
		public int geoToCoordBufSize = -1;
		public int geoToColorBuf = -1;
		public int geoToNormalBuf = -1;
		public SparseArray<Integer> geoToTexCoordsBuf = new SparseArray<Integer>();
		public SparseArray<Integer> geoToVertAttribBuf = new SparseArray<Integer>();

		//Every thing below relates to interleaved data
		public int coordBufId = -1; // if separate
		public int interleavedBufId = -1;
		public int interleavedStride = 0;
		public int geoToCoordOffset = -1;
		public int geoToColorsOffset = -1;
		public int geoToNormalsOffset = -1;
		public int[] geoToVattrOffset = new int[10];
		public int[] geoToTexCoordOffset = new int[10];

		// vertex array object id for this geom
		public int vaoId = -1;

		//used to identify each geometry as we see it
		private static int nextNativeId = 0;

		public GeometryData()
		{
			nativeId = nextNativeId++;
			nextNativeId = nextNativeId > Integer.MAX_VALUE - 10 ? 0 : nextNativeId;// desperate loop
		}

	}

	public SparseArray<ProgramData> allProgramData = new SparseArray<ProgramData>();

	public static class ProgramData
	{
		public HashMap<String, Integer> progToGenVertAttNameToGenVertAttIndex = new HashMap<String, Integer>();
		public LocationData programToLocationData = null;// null to indicate need to load
		public ByteBuffer programToUBOBB = null;
		public int programToUBOBuf = -1;
	}

	public fogData fogData = new fogData();

	public glFrontMaterial materialData = new glFrontMaterial();
	// should use getMaximumLights() in pipeline? though it's up to the shader
	public static int MAX_LIGHTS = 32;
	public int maxLights;
	public int numberOfLights;
	public glLightSource[] glLightSource = new glLightSource[MAX_LIGHTS];

	//See here http://download.java.net/media/java3d/javadoc/1.3.2/javax/media/j3d/RenderingAttributes.html
	// For coloring implementation details

	//only for no lighting, materialDiffuse or vertex colors otherwise
	public Vector4f objectColor = new Vector4f();

	public float pointSize = 0;

	public boolean pointsEnabled = false;
	
	public int polygonMode = PolygonAttributes.POLYGON_FILL;

	public static class RenderingData
	{
		public boolean alphaTestEnabled = false;
		public int alphaTestFunction = RenderingAttributes.ALWAYS;
		public float alphaTestValue = 0;
		public int ignoreVertexColors; //-1 is not set 1,0 bool
	}

	public RenderingData renderingData = new RenderingData();

	public float transparencyAlpha = 0;

	public Vector4f currentAmbientColor = new Vector4f();

	public Matrix4d textureTransform = new Matrix4d();

	//various ffp matrixes
	public Matrix4d currentModelMat = new Matrix4d();
	public Matrix4d currentViewMat = new Matrix4d();
	public Matrix4d currentModelViewMat = new Matrix4d();
	public Matrix4d currentModelViewMatInverse = new Matrix4d();
	public Matrix4d currentModelViewProjMat = new Matrix4d();
	public Matrix3d currentNormalMat = new Matrix3d();
	public Matrix4d currentProjMat = new Matrix4d();
	public Matrix4d currentProjMatInverse = new Matrix4d();

	/**
	 * On shader creation the various FFP locations are discovered and recorded for use later
	 * @author phil
	 *
	 */
	public static class LocationData
	{
		//normal uniform data
		public int glProjectionMatrix = -1;
		public int glProjectionMatrixInverse = -1;
		public int glModelMatrix = -1;
		public int glViewMatrix = -1;
		public int glModelViewMatrix = -1;
		public int glModelViewMatrixInverse = -1;
		public int glModelViewProjectionMatrix = -1;
		public int glNormalMatrix = -1;
		public int ignoreVertexColors = -1;
		public int glLightModelambient = -1;
		public int objectColor = -1;
		public int transparencyAlpha = -1;
		public int alphaTestEnabled = -1;
		public int alphaTestFunction = -1;
		public int alphaTestValue = -1;
		public int textureTransform = -1;

		public fogDataLocs fogData = new fogDataLocs();

		public glFrontMaterialLocs glFrontMaterial = new glFrontMaterialLocs();
		public int numberOfLights = -1;
		public glLightSourceLocs[] glLightSource = new glLightSourceLocs[MAX_LIGHTS];

		public int glVertex = -1;
		public int glColor = -1;
		public int glNormal = -1;

		public int[] glMultiTexCoord = new int[16];
		public SparseArray<Integer> genAttIndexToLoc = new SparseArray<Integer>();

	}

	/**
	 *  below here are openGL state tracking to reduce unnecessary native calls
	 *  Note this is NOT like the "new" or so called current staet above taht needs to be st in the FFP
	 *  call, this is the old or previously set data, that might not need to be updated
	 * @author phil
	 *
	 */
	public static class GL_State
	{
		public boolean depthBufferEnableOverride;
		public boolean depthBufferEnable;
		public int depthTestFunction;
		public boolean depthBufferWriteEnableOverride;
		public boolean depthBufferWriteEnable;
		public boolean userStencilAvailable;
		public boolean stencilEnable;
		public boolean glDepthMask;
		public boolean glEnableGL_STENCIL_TEST;
		public int stencilFailOp;
		public int stencilZFailOp;
		public int stencilZPassOp;
		public int stencilFunction;
		public int stencilReferenceValue;
		public int stencilCompareMask;
		public int stencilWriteMask;
		public int[] setGLSLUniform1i = new int[500];
		public int[] clearer1 = new int[500];
		public float[] setGLSLUniform1f = new float[500];
		public float[] clearer2 = new float[500];
		public boolean glEnableGL_BLEND;
		public int srcBlendFunction;
		public int dstBlendFunction;
		public int glActiveTexture;
		public int currentProgramId;
		public int[] glBindTextureGL_TEXTURE_2D = new int[35000];// indexed based on current glActiveTexture
		public int[] clearer3 = new int[35000];
		public int cullFace;
		public float polygonOffsetFactor;
		public float polygonOffset;

		public int ignoreVertexColors; //-1 indicates not set yet, always set
		public Vector4f glLightModelambient = new Vector4f();
		public Vector4f objectColor = new Vector4f();
		public float transparencyAlpha;
		public Matrix4d textureTransform = new Matrix4d();
		public Matrix4d modelMatrix = new Matrix4d();
		public Matrix4d glModelViewMatrix = new Matrix4d();
		public Matrix4d glModelViewMatrixInverse = new Matrix4d();
		public Matrix4d glModelViewProjectionMatrix = new Matrix4d();
		public Matrix3d glNormalMatrix = new Matrix3d();
		public boolean alphaTestEnabled = false;
		public int alphaTestFunction;
		public float alphaTestValue;

		public fogData fogData = new fogData();
		public glFrontMaterial glFrontMaterial = new glFrontMaterial();
		public int numberOfLights = -1;
		public glLightSource[] glLightSource = new glLightSource[MAX_LIGHTS];

		public void clear()
		{
			depthBufferEnableOverride = false;
			depthBufferEnable = false;
			depthTestFunction = -1;
			depthBufferWriteEnableOverride = false;
			depthBufferWriteEnable = false;
			userStencilAvailable = false;
			stencilEnable = false;
			glDepthMask = false;
			glEnableGL_STENCIL_TEST = false;
			stencilFailOp = -1;
			stencilZFailOp = -1;
			stencilZPassOp = -1;
			stencilFunction = -1;
			stencilReferenceValue = -1;
			stencilCompareMask = -1;
			stencilWriteMask = -1;
			System.arraycopy(clearer1, 0, setGLSLUniform1i, 0, setGLSLUniform1i.length);
			System.arraycopy(clearer2, 0, setGLSLUniform1f, 0, setGLSLUniform1f.length);
			glEnableGL_BLEND = false;
			srcBlendFunction = -1;
			dstBlendFunction = -1;
			glActiveTexture = -1;
			currentProgramId = -1;
			System.arraycopy(clearer3, 0, glBindTextureGL_TEXTURE_2D, 0, glBindTextureGL_TEXTURE_2D.length);
			cullFace = -1;
			polygonOffsetFactor = -1;
			polygonOffset = -1;
			ignoreVertexColors = -1;
			glLightModelambient.set(Float.NEGATIVE_INFINITY, -999f, -999f, -999f);
			objectColor.set(Float.NEGATIVE_INFINITY, -999f, -999f, -999f);
			transparencyAlpha = -1;
			textureTransform.m00 = Double.NEGATIVE_INFINITY;
			modelMatrix.m00 = Double.NEGATIVE_INFINITY;
			glModelViewMatrix.m00 = Double.NEGATIVE_INFINITY;
			glModelViewMatrixInverse.m00 = Double.NEGATIVE_INFINITY;
			glModelViewProjectionMatrix.m00 = Double.NEGATIVE_INFINITY;
			glNormalMatrix.m00 = Double.NEGATIVE_INFINITY;
			alphaTestEnabled = false;
			alphaTestFunction = -1;
			alphaTestValue = -99f;

			fogData.clear();
			glFrontMaterial.clear();
			for (int i = 0; i < MAX_LIGHTS; i++)
			{
				glLightSource[i] = null;
			}
		}
	}

	public GL_State gl_state = new GL_State();

	/**
	 *  program used on last run through of FFP, so nearly like gl_state above, just a desperate attempt
	 *  to see if the uniform locations have changed even if a new shader is being used
	 */
	public int prevShaderProgram;

	// The per frame stats
	public Jogl2es2PerFrameStats perFrameStats = new Jogl2es2PerFrameStats();

	private int statsFrame = 0;
	private int STATS_OUTPUT_FRAME_FREQ = 50;

	public void outputPerFrameData()
	{
		statsFrame++;
		if (statsFrame % STATS_OUTPUT_FRAME_FREQ == 0)
		{
			statsFrame = 0;
			System.out.println("======================================================");
			perFrameStats.outputPerFrameData();
		}
		// clear for next frame
		perFrameStats = new Jogl2es2PerFrameStats();
		perFrameStats.endOfPrevFrameTime = System.nanoTime();
	}
	
	// texture and raster fill variables
	
	// raster vao and buf are not in the by geom bucket because I don't get given geom
	// background has to be created and destroyed

	public int simpleTextureShaderProgramId = -1;
	public int simpleTextureShaderProgramVertLoc = -1;
	public int simpleTextureShaderProgramTexCoordLoc = -1;
	public int simpleTextureShaderProgramBaseMapLoc = -1;
	
	// just a singleton of the handy matrix/array operations
	public Jogl2es2MatrixUtil matrixUtil = new Jogl2es2MatrixUtil();

	/////////////////////////////////////S H A D E R   S T R U C T S /////////////////////////////////////////////////////

	// in the shader as follows
	// struct material
	// {
	// 	int lightEnabled;
	// 	vec4 ambient;
	// 	vec4 diffuse;
	// 	vec4 emission;
	// 	vec3 specular;
	// 	float shininess;
	// };
	// uniform material glFrontMaterial;
	public static class glFrontMaterial
	{
		public int lightEnabled = -1;
		public Vector4f ambient = new Vector4f();
		public Vector4f diffuse = new Vector4f();
		public Vector3f emission = new Vector3f();
		public Vector3f specular = new Vector3f();
		public float shininess;

		public void clear()
		{
			lightEnabled = -1;
			ambient.set(Float.NEGATIVE_INFINITY, -999f, -999f, -999f);
			diffuse.set(Float.NEGATIVE_INFINITY, -999f, -999f, -999f);
			emission.set(Float.NEGATIVE_INFINITY, -999f, -999f);
			specular.set(Float.NEGATIVE_INFINITY, -999f, -999f);
			shininess = -99;
		}

		@Override
		public boolean equals(Object o)
		{
			if (o instanceof glFrontMaterial)
			{
				glFrontMaterial ogfm = (glFrontMaterial) o;
				return ogfm.lightEnabled == lightEnabled && ogfm.ambient.equals(ambient) && ogfm.diffuse.equals(diffuse)
						&& ogfm.emission.equals(emission) && ogfm.specular.equals(specular) && ogfm.shininess == shininess;
			}
			else
			{
				return false;
			}
		}
	}

	public static class glFrontMaterialLocs
	{
		public boolean present = false;
		public int lightEnabled = -1;
		public int ambient = -1;
		public int diffuse = -1;
		public int emission = -1;
		public int specular = -1;
		public int shininess = -1;

		public void setPresent()
		{
			present = lightEnabled != -1 || ambient != -1 || diffuse != -1 || emission != -1 || specular != -1 || shininess != -1;
		}
	}
	//	struct lightSource
	//	{
	//	  vec4 position;
	//	  vec4 diffuse;
	//	  vec4 specular;
	//	  float constantAttenuation, linearAttenuation, quadraticAttenuation;
	//	  float spotCutoff, spotExponent;
	//	  vec3 spotDirection;
	//	};
	//
	//	uniform int numberOfLights;
	//	const int maxLights = 2;
	//	uniform lightSource glLightSource[maxLights];

	//see https://en.wikibooks.org/wiki/GLSL_Programming/GLUT/Multiple_Lights
	public static class glLightSource
	{
		public int enabled = -1;
		public int prevLightSlot = -1;
		public Vector4f position = new Vector4f();
		//public Vector4f ambient = new Vector4f();//removed as an oddity
		public Vector4f diffuse = new Vector4f();
		public Vector4f specular = new Vector4f();
		public float constantAttenuation;
		public float linearAttenuation;
		public float quadraticAttenuation;
		public float spotCutoff;
		public float spotExponent;
		public Vector3f spotDirection = new Vector3f();

		public void clear()
		{
			enabled = -1;
			prevLightSlot = -1;
			position.set(-999f, -999f, -999f, -999f);
			diffuse.set(-999f, -999f, -999f, -999f);
			specular.set(-999f, -999f, -999f, -999f);
			constantAttenuation = -99;
			linearAttenuation = -99;
			quadraticAttenuation = -99;
			spotCutoff = -99;
			spotExponent = -99;
			spotDirection.set(-999f, -999f, -999f);
		}

		public void set(glLightSource ogfm)
		{
			enabled = ogfm.enabled;
			prevLightSlot = ogfm.prevLightSlot;
			position.set(ogfm.position);
			diffuse.set(ogfm.diffuse);
			specular.set(ogfm.specular);
			constantAttenuation = ogfm.constantAttenuation;
			linearAttenuation = ogfm.linearAttenuation;
			quadraticAttenuation = ogfm.quadraticAttenuation;
			spotCutoff = ogfm.spotCutoff;
			spotExponent = ogfm.spotExponent;
			spotDirection.set(ogfm.spotDirection);
		}

		@Override
		public boolean equals(Object o)
		{
			if (o instanceof glLightSource)
			{
				glLightSource ogfm = (glLightSource) o;
				return enabled == ogfm.enabled && prevLightSlot == ogfm.prevLightSlot && ogfm.position.equals(position)
						&& ogfm.diffuse.equals(diffuse) && ogfm.specular.equals(specular) && ogfm.constantAttenuation == constantAttenuation
						&& ogfm.linearAttenuation == linearAttenuation && ogfm.quadraticAttenuation == quadraticAttenuation
						&& ogfm.spotCutoff == spotCutoff && ogfm.spotExponent == spotExponent && ogfm.spotDirection.equals(spotDirection);
			}
			else
			{
				return false;
			}
		}
	}

	public static class glLightSourceLocs
	{
		public int position = -1;
		public int diffuse = -1;
		public int specular = -1;
		public int constantAttenuation = -1;
		public int linearAttenuation = -1;
		public int quadraticAttenuation = -1;
		public int spotCutoff = -1;
		public int spotExponent = -1;
		public int spotDirection = -1;
	}

	// in the shader as follows
	// struct fogDataStruct
	// {
	// int fogEnabled;
	// vec4 expColor;
	// float expDensity;
	// vec4 linearColor;
	// float linearStart;
	// float linearEnd;
	// };
	// uniform fogDataStruct fogData;
	public static class fogData
	{
		public int fogEnabled = -1;
		public Vector3f expColor = new Vector3f();
		public float expDensity;
		public Vector3f linearColor = new Vector3f();
		public float linearStart;
		public float linearEnd;

		public void clear()
		{
			fogEnabled = -1;
			expColor.set(Float.NEGATIVE_INFINITY, -999f, -999f);
			expDensity = -99;
			linearColor.set(-999f, -999f, -999f);
			linearStart = -99;
			linearEnd = -99;
		}

		public void set(fogData ogfm)
		{
			fogEnabled = ogfm.fogEnabled;
			expColor.set(ogfm.expColor);
			expDensity = ogfm.expDensity;
			linearColor.set(ogfm.linearColor);
			linearStart = ogfm.linearStart;
			linearEnd = ogfm.linearEnd;
		}

		@Override
		public boolean equals(Object o)
		{
			if (o instanceof fogData)
			{
				fogData ogfm = (fogData) o;
				return ogfm.fogEnabled == fogEnabled && ogfm.expColor.equals(expColor) && ogfm.expDensity == expDensity
						&& ogfm.linearColor.equals(linearColor) && ogfm.linearStart == linearStart && ogfm.linearEnd == linearEnd;
			}
			else
			{
				return false;
			}
		}
	}

	public static class fogDataLocs
	{
		public boolean present = false;
		public int fogEnabled = -1;
		public int expColor = -1;
		public int expDensity = -1;
		public int linearColor = -1;
		public int linearStart = -1;
		public int linearEnd = -1;

		public void setPresent()
		{
			present = fogEnabled != -1 || expColor != -1 || expDensity != -1 || linearColor != -1 || linearStart != -1 || linearEnd != -1;
		}
	}
}
