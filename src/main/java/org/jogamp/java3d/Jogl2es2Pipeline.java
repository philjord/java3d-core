/*
 * Copyright (c) 2016 JogAmp Community. All rights reserved.
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

import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsConfigTemplate;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Window;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jogamp.java3d.Jogl2es2Context.GeometryData;
import org.jogamp.java3d.Jogl2es2Context.LocationData;
import org.jogamp.java3d.Jogl2es2Context.ProgramData;
import org.jogamp.java3d.Jogl2es2Context.glLightSource;
import org.jogamp.java3d.Jogl2es2Context.glLightSourceLocs;
import org.jogamp.vecmath.SingularMatrixException;
import org.jogamp.vecmath.Vector4f;

import com.jogamp.common.nio.Buffers;
import com.jogamp.common.os.Platform;
import com.jogamp.common.util.VersionNumber;
import com.jogamp.nativewindow.AbstractGraphicsDevice;
import com.jogamp.nativewindow.AbstractGraphicsScreen;
import com.jogamp.nativewindow.CapabilitiesChooser;
import com.jogamp.nativewindow.GraphicsConfigurationFactory;
import com.jogamp.nativewindow.NativeSurface;
import com.jogamp.nativewindow.NativeWindowFactory;
import com.jogamp.nativewindow.OffscreenLayerOption;
import com.jogamp.nativewindow.ProxySurface;
import com.jogamp.nativewindow.UpstreamSurfaceHook;
import com.jogamp.nativewindow.VisualIDHolder;
import com.jogamp.nativewindow.awt.AWTGraphicsConfiguration;
import com.jogamp.nativewindow.awt.AWTGraphicsDevice;
import com.jogamp.nativewindow.awt.AWTGraphicsScreen;
import com.jogamp.nativewindow.awt.JAWTWindow;
import com.jogamp.opengl.DefaultGLCapabilitiesChooser;
import com.jogamp.opengl.FBObject;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL2ES2;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLCapabilitiesChooser;
import com.jogamp.opengl.GLCapabilitiesImmutable;
import com.jogamp.opengl.GLContext;
import com.jogamp.opengl.GLDrawable;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.GLFBODrawable;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.Threading;

/**
 * Concrete implementation of Pipeline class for the GL2ES2 rendering pipeline.
 */
class Jogl2es2Pipeline extends Jogl2es2DEPPipeline
{
	//Note this is VERY expensive and should be false unless debugging
	private static final boolean DO_OUTPUT_ERRORS = false;
	// Currently prints for entry points already implemented
	static final boolean VERBOSE = false;
	// Debugging output for graphics configuration selection
	private static final boolean DEBUG_CONFIG = false;
	// Prints extra debugging information
	private static final boolean EXTRA_DEBUGGING = false;

	private static final boolean OUTPUT_PER_FRAME_STATS = false;

	private static final boolean MINIMISE_NATIVE_CALLS_FFP = true;

	private static final boolean MINIMISE_NATIVE_CALLS_TRANSPARENCY = true;
	private static final boolean MINIMISE_NATIVE_CALLS_TEXTURE = true;

	private static final boolean MINIMISE_NATIVE_SHADER = true;
	private static final boolean MINIMISE_NATIVE_CALLS_OTHER = true;

	// This MUST be true on android fullscreen 
	// setPosition on a GLWindow can lock-up if true
	// also with on and offscreen must be false too
	private static final boolean NEVER_RELEASE_CONTEXT = false;
	
	
	private static boolean quadArrayCautionPrinted = false;

	/**
	 * Constructor for singleton JoglPipeline instance
	 */
	protected Jogl2es2Pipeline()
	{

	}

	/**
	 * Initialize the pipeline
	 */
	@Override
	void initialize(Pipeline.Type pipelineType)
	{
		super.initialize(pipelineType);

		// Java3D maintains strict control over which threads perform OpenGL work
		Threading.disableSingleThreading();

		profile = GLProfile.get(GLProfile.GL2ES2);
		// profile = GLProfile.getMaxProgrammable(true);

	}

	// FIXME: ugly hack for buffer clearing on removal of a geometry
	public void registerClearBuffers(Context ctx, GeometryArrayRetained geo)
	{
		Jogl2es2Context joglesctx = (Jogl2es2Context) ctx;
		synchronized (joglesctx.geoToClearBuffers)
		{
			joglesctx.geoToClearBuffers.add(geo);
		}
	}

	private static void doClearBuffers(Context ctx)
	{
		Jogl2es2Context joglesctx = (Jogl2es2Context) ctx;
		
		if (joglesctx.geoToClearBuffers.size() > 0)
		{
			synchronized (joglesctx.geoToClearBuffers)
			{
				GL2ES2 gl = joglesctx.gl2es2();
				for (GeometryArrayRetained geo : joglesctx.geoToClearBuffers)
				{
					GeometryData gd = joglesctx.allGeometryData.get(geo.nativeId);
					joglesctx.allGeometryData.remove(geo.nativeId);
					geo.nativeId = -1;

					// TODO: why exactly is the same geo being removed twice?
					if (gd != null)
					{
						if (gd.geoToIndBuf != -1)

							gl.glDeleteBuffers(1, new int[] { gd.geoToIndBuf }, 0);
						if (gd.geoToCoordBuf != -1)
							gl.glDeleteBuffers(1, new int[] { gd.geoToCoordBuf }, 0);
						if (gd.geoToColorBuf != -1)
							gl.glDeleteBuffers(1, new int[] { gd.geoToColorBuf }, 0);
						if (gd.geoToNormalBuf != -1)
							gl.glDeleteBuffers(1, new int[] { gd.geoToNormalBuf }, 0);

						int[] bufIds = gd.geoToIndStripBuf;
						if (bufIds != null && bufIds.length > 0)
						{
							gl.glDeleteBuffers(bufIds.length, bufIds, 0);
						}

						SparseArray<Integer> tcBufIds = gd.geoToTexCoordsBuf;
						if (tcBufIds != null)
						{
							for (int i = 0; i < tcBufIds.size(); i++)
							{
								Integer tcBufId = tcBufIds.get(tcBufIds.keyAt(i));

								if (tcBufId != null)
									gl.glDeleteBuffers(1, new int[] { tcBufId.intValue() }, 0);
							}
							tcBufIds.clear();
						}

						SparseArray<Integer> vaBufIds = gd.geoToVertAttribBuf;
						if (vaBufIds != null)
						{
							for (int i = 0; i < vaBufIds.size(); i++)
							{
								Integer vaBufId = vaBufIds.get(vaBufIds.keyAt(i));
								if (vaBufId != null)
									gl.glDeleteBuffers(1, new int[] { vaBufId.intValue() }, 0);
							}
							vaBufIds.clear();
						}

						if (gd.interleavedBufId != -1)
							gl.glDeleteBuffers(1, new int[] { gd.interleavedBufId }, 0);

						if (gd.vaoId != -1)
							((GL2ES3) gl).glDeleteVertexArrays(1, new int[] { gd.vaoId }, 0);

					}

				}

				if (OUTPUT_PER_FRAME_STATS)
					joglesctx.perFrameStats.geoToClearBuffers = joglesctx.geoToClearBuffers.size();

				joglesctx.geoToClearBuffers.clear();

				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}
		}
	}

	// ---------------------------------------------------------------------

	//
	// GeometryArrayRetained methods
	//

	// used for GeometryArrays by Copy or interleaved
	@Override
	void execute(Context ctx, GeometryArrayRetained geo, int geo_type, boolean isNonUniformScale, boolean useAlpha,
			boolean ignoreVertexColors, int startVIndex, int vcount, int vformat, int texCoordSetCount, int[] texCoordSetMap,
			int texCoordSetMapLen, int[] texUnitOffset, int numActiveTexUnitState, int vertexAttrCount, int[] vertexAttrSizes,
			float[] varray, float[] carray, int cDirty)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.execute()");

		executeGeometryArray(ctx, geo, geo_type, isNonUniformScale, useAlpha, ignoreVertexColors, startVIndex, vcount, vformat,
				texCoordSetCount, texCoordSetMap, texCoordSetMapLen, texUnitOffset, numActiveTexUnitState, vertexAttrCount, vertexAttrSizes,
				varray, null, carray, cDirty);
	}

	// used by GeometryArray by Reference in interleaved format with NIO buffer
	@Override
	void executeInterleavedBuffer(Context ctx, GeometryArrayRetained geo, int geo_type, boolean isNonUniformScale, boolean useAlpha,
			boolean ignoreVertexColors, int startVIndex, int vcount, int vformat, int texCoordSetCount, int[] texCoordSetMap,
			int texCoordSetMapLen, int[] texUnitOffset, int numActiveTexUnit, FloatBuffer varray, float[] cdata, int cdirty)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.executeInterleavedBuffer()");

		executeGeometryArray(ctx, geo, geo_type, isNonUniformScale, useAlpha, ignoreVertexColors, startVIndex, vcount, vformat,
				texCoordSetCount, texCoordSetMap, texCoordSetMapLen, texUnitOffset, numActiveTexUnit, 0, null, null, varray, cdata, cdirty);
	}

	private void executeGeometryArray(Context absCtx, GeometryArrayRetained geo, int geo_type, boolean isNonUniformScale, boolean useAlpha,
			boolean ignoreVertexColors, int startVIndex, int vcount, int vformat, int texCoordSetCount, int[] texCoordSetMap,
			int texCoordSetMapLen, int[] texCoordSetMapOffset, int numActiveTexUnitState, int vertexAttrCount, int[] vertexAttrSizes,
			float[] varray, FloatBuffer varrayBuffer, float[] carray, int cDirty)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.executeGeometryArray()");

		Jogl2es2Context ctx = (Jogl2es2Context) absCtx;
		int shaderProgramId = ctx.shaderProgramId;

		if (shaderProgramId != -1)
		{
			GL2ES2 gl = ctx.gl2es2();
			ProgramData pd = ctx.programData;
			LocationData locs = pd.programToLocationData;

			setFFPAttributes(ctx, gl, shaderProgramId, pd, vformat, ignoreVertexColors);

			int stride = 0, coordoff = 0, normoff = 0, coloroff = 0, texCoordoff = 0;
			int texSize = 0, texStride = 0;
			int vAttrOff = 0;
			int vAttrStride = 0;
			int bstride = 0, cbstride = 0;

			int[] sarray = null;
			int[] start_array = null;

			if (EXTRA_DEBUGGING)
			{
				System.err.println("Vertex format: " + getVertexDescription(vformat));
				System.err.println("Geometry type: " + getGeometryDescription(geo_type));
				if (carray != null)
				{
					System.err.println("  Separate color array");
				}
				else
				{
					System.err.println("  Colors (if any) interleaved");
				}
			}

			if ((vformat & GeometryArray.COORDINATES) != 0)
			{
				stride += 3;
			}
			if ((vformat & GeometryArray.NORMALS) != 0)
			{
				stride += 3;
				coordoff += 3;
			}
			if ((vformat & GeometryArray.COLOR) != 0)
			{
				if ((vformat & GeometryArray.WITH_ALPHA) != 0)
				{
					stride += 4;
					normoff += 4;
					coordoff += 4;
				}
				else
				{
					// Handle the case of executeInterleaved 3f 
					stride += 3;
					normoff += 3;
					coordoff += 3;
				}
			}
			if ((vformat & GeometryArray.TEXTURE_COORDINATE) != 0)
			{
				if (EXTRA_DEBUGGING)
				{
					System.err.println("Number of tex coord sets: " + texCoordSetCount);
				}
				if ((vformat & GeometryArray.TEXTURE_COORDINATE_2) != 0)
				{
					texSize = 2;
					texStride = 2 * texCoordSetCount;
				}
				else if ((vformat & GeometryArray.TEXTURE_COORDINATE_3) != 0)
				{
					texSize = 3;
					texStride = 3 * texCoordSetCount;
				}
				else if ((vformat & GeometryArray.TEXTURE_COORDINATE_4) != 0)
				{
					texSize = 4;
					texStride = 4 * texCoordSetCount;
				}
				stride += texStride;
				normoff += texStride;
				coloroff += texStride;
				coordoff += texStride;
			}
			if ((vformat & GeometryArray.VERTEX_ATTRIBUTES) != 0)
			{
				for (int i = 0; i < vertexAttrCount; i++)
				{
					vAttrStride += vertexAttrSizes[i];
				}
				stride += vAttrStride;
				normoff += vAttrStride;
				coloroff += vAttrStride;
				coordoff += vAttrStride;
				texCoordoff += vAttrStride;
			}

			bstride = stride * Buffers.SIZEOF_FLOAT;

			if (geo_type == GeometryRetained.GEO_TYPE_TRI_STRIP_SET || geo_type == GeometryRetained.GEO_TYPE_TRI_FAN_SET
					|| geo_type == GeometryRetained.GEO_TYPE_LINE_STRIP_SET)
			{
				sarray = ((GeometryStripArrayRetained) geo).stripVertexCounts;
				start_array = ((GeometryStripArrayRetained) geo).stripStartOffsetIndices;
			}

			// using byRef interleaved array and has a separate pointer, then ..
			int cstride = stride;
			if (carray != null)
			{
				cstride = 4;
			}

			cbstride = cstride * Buffers.SIZEOF_FLOAT;

			int startVertex = stride * startVIndex;
			int startClrs = cstride * startVIndex;
			if (carray == null)
			{
				startClrs += coloroff;
			}

			if (EXTRA_DEBUGGING)
			{
				System.err.println("  startVertex: " + startVertex);
				System.err.println("  stride: " + stride);
				System.err.println("  bstride: " + bstride);
				System.err.println("  normoff: " + normoff);
				System.err.println("  coloroff: " + coloroff);
				System.err.println("  coordoff: " + coordoff);
				System.err.println("  texCoordoff: " + texCoordoff);
			}

			GeometryData gd = loadAllBuffers(ctx, gl, geo, ignoreVertexColors, vcount, vformat, vformat, varrayBuffer, varray, startVertex,
					varrayBuffer, carray, startClrs);

			// GeometryArray.ALLOW_REF_DATA_WRITE is just my indicator of changeability
			boolean morphable = geo.source.getCapability(GeometryArray.ALLOW_REF_DATA_WRITE)
					|| geo.source.getCapability(GeometryArray.ALLOW_COORDINATE_WRITE);

			// not required second time around for VAO (except morphable coords)
			boolean bindingRequired = true;
			// Note although we ask for ES2 we can get ES3, which demands a VAO or nothing renders
			if (gl.isGL2ES3())
			{
				GL2ES3 gl2es3 = (GL2ES3)gl;
				if (gd.vaoId == -1)
				{
					int[] tmp = new int[1];
					gl2es3.glGenVertexArrays(1, tmp, 0);
					gd.vaoId = tmp[0];
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);
				}
				else
				{
					bindingRequired = false;
				}
				gl2es3.glBindVertexArray(gd.vaoId);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}

			// refill the buffers in case of writeable data (morphable)
			if (locs.glVertex != -1)
			{
				if (gd.geoToCoordBuf == -1)
				{
					new Throwable("Buffer load issue!").printStackTrace();
				}
				else
				{
					// rather than the morphable system above we should be able to use cDirty, but it appears to be wrong
					// if ((cDirty & GeometryArrayRetained.COORDINATE_CHANGED) != 0)
					if (morphable)
					{
						FloatBuffer verts = null;
						// do we need to covert a float[]
						if (varray != null)
						{
							verts = getVertexArrayBuffer(varray);
						}
						else
						{
							verts = varrayBuffer;
						}

						verts.position(startVertex);
						// Sometime the FloatBuffer is swapped out for bigger or smaller
						if (gd.geoToCoordBufSize != verts.remaining())
						{
							System.err.println("Morphable buffer changed " + gd.geoToCoordBufSize + " != " + verts.remaining()
									+ " un indexed ((GeometryArray) geo.source) " + ((GeometryArray) geo.source).getName() + " "
									+ geo.source + ", this is not nessasarily a problem");

							int prevBufId1 = gd.geoToCoordBuf1;// record these in order to delete after re-bind
							int prevBufId2 = gd.geoToCoordBuf2;

							int[] tmp = new int[2];
							gl.glGenBuffers(2, tmp, 0);
							gd.geoToCoordBuf = tmp[0];
							gd.geoToCoordBuf1 = tmp[0];
							gd.geoToCoordBuf2 = tmp[1];

							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf1);
							gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (verts.remaining() * Float.SIZE / 8), verts, GL2ES2.GL_DYNAMIC_DRAW);
							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf2);
							gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (verts.remaining() * Float.SIZE / 8), verts, GL2ES2.GL_DYNAMIC_DRAW);

							gd.geoToCoordBufSize = verts.remaining();

							if (DO_OUTPUT_ERRORS)
								outputErrors(ctx);

							if (OUTPUT_PER_FRAME_STATS)
								ctx.perFrameStats.glBufferData++;

							//Notice no check for bindingRequired as we are altering the binding
							//and previously used buffer is deleted AFTER re-bind

							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
							gl.glVertexAttribPointer(locs.glVertex, 3, GL2ES2.GL_FLOAT, false, 0, 0);
							gl.glEnableVertexAttribArray(locs.glVertex);
							if (DO_OUTPUT_ERRORS)
								outputErrors(ctx);

							if (OUTPUT_PER_FRAME_STATS)
								ctx.perFrameStats.glVertexAttribPointerCoord++;

							if (OUTPUT_PER_FRAME_STATS)
								ctx.perFrameStats.coordCount += gd.geoToCoordBufSize;

							gl.glDeleteBuffers(1, new int[] { prevBufId1, prevBufId2 }, 0);
							if (DO_OUTPUT_ERRORS)
								outputErrors(ctx);
						}
						else
						{
							// work out the buffer to update and buffer to swap to
							if (gd.geoToCoordBuf == gd.geoToCoordBuf1)
							{
								// update 1 but set to draw 2
								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf1);
								gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, (verts.remaining() * Float.SIZE / 8), verts);
								gd.geoToCoordBuf = gd.geoToCoordBuf2;
							}
							else
							{
								// update 2 but set to draw 1
								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf2);
								gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, (verts.remaining() * Float.SIZE / 8), verts);
								gd.geoToCoordBuf = gd.geoToCoordBuf1;
							}

							if (DO_OUTPUT_ERRORS)
								outputErrors(ctx);

							if (OUTPUT_PER_FRAME_STATS)
								ctx.perFrameStats.glBufferSubData++;
						}
					}
				}
			}
			else
			{
				throw new UnsupportedOperationException("Shader has no glVertex.\n" + VALID_FORMAT_MESSAGE);
			}

			// update other attributes if required
			if (((vformat & GeometryArray.COLOR) != 0) && locs.glColor != -1 && !ignoreVertexColors)
			{
				// if ((cDirty & GeometryArrayRetained.COLOR_CHANGED) != 0)
				boolean changable = geo.source.getCapability(GeometryArray.ALLOW_COLOR_WRITE);
				if (changable)
				{
					FloatBuffer verts = null;
					FloatBuffer clrs = null;
					// do we need to covert a float[]
					if (varray != null)
					{
						verts = getVertexArrayBuffer(varray);
					}
					else
					{
						verts = varrayBuffer;
					}
					if (carray != null)
					{
						verts = getColorArrayBuffer(carray);
					}
					else
					{
						clrs = verts;
					}

					clrs.position(0);
					gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToColorBuf);
					gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, clrs.remaining() * Float.SIZE / 8, clrs);
				}
			}

			// notice morphables must always rebind each frame as coord buffers are swapped, so the vao 
			// (if it is bound points to the previous buffer)
			if (bindingRequired || morphable)
			{
				// always do coords 
				gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
				gl.glVertexAttribPointer(locs.glVertex, 3, GL2ES2.GL_FLOAT, false, bstride,
						(startVertex + coordoff) * Buffers.SIZEOF_FLOAT);
				gl.glEnableVertexAttribArray(locs.glVertex);

				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glVertexAttribPointerCoord++;
				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.coordCount += gd.geoToCoordBufSize;
			}

			if (bindingRequired)
			{
				if (((vformat & GeometryArray.COLOR) != 0) && locs.glColor != -1 && !ignoreVertexColors)
				{
					if (gd.geoToColorBuf == -1)
					{
						new Throwable("Buffer load issue!").printStackTrace();
					}
					else
					{
						int sz = ((vformat & GeometryArray.WITH_ALPHA) != 0) ? 4 : 3;

						gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToColorBuf);
						gl.glVertexAttribPointer(locs.glColor, sz, GL2ES2.GL_FLOAT, false, cbstride,
								(startVertex + coloroff) * Buffers.SIZEOF_FLOAT);
						gl.glEnableVertexAttribArray(locs.glColor);
						if (DO_OUTPUT_ERRORS)
							outputErrors(ctx);

						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glVertexAttribPointerColor++;
					}
				}
				else if (locs.glColor != -1)
				{
					// ignoreVertexcolors will have been set in FFP, now as the glColors is unbound
					gl.glDisableVertexAttribArray(locs.glColor);
					if (OUTPUT_PER_FRAME_STATS)
						ctx.perFrameStats.glDisableVertexAttribArray++;
				}

				if (((vformat & GeometryArray.NORMALS) != 0) && locs.glNormal != -1)
				{
					if (gd.geoToCoordBuf == -1)
					{
						new Throwable("Buffer load issue!").printStackTrace();
					}
					else
					{
						gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
						gl.glVertexAttribPointer(locs.glNormal, 3, GL2ES2.GL_FLOAT, false, bstride,
								(startVertex + normoff) * Buffers.SIZEOF_FLOAT);
						gl.glEnableVertexAttribArray(locs.glNormal);
						if (DO_OUTPUT_ERRORS)
							outputErrors(ctx);

						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glVertexAttribPointerNormals++;
					}
				}
				else
				{
					if (locs.glNormal != -1)
					{
						gl.glDisableVertexAttribArray(locs.glNormal);
						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glDisableVertexAttribArray++;
					}
				}

				if ((vformat & GeometryArray.VERTEX_ATTRIBUTES) != 0)
				{
					int vAttrOffset = startVertex + vAttrOff;
					for (int index = 0; index < vertexAttrCount; index++)
					{
						Integer attribLoc = locs.genAttIndexToLoc.get(index);
						if (attribLoc != null && attribLoc.intValue() != -1)
						{
							int sz = vertexAttrSizes[index];

							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
							gl.glVertexAttribPointer(attribLoc.intValue(), sz, GL2ES2.GL_FLOAT, false, bstride,
									vAttrOffset * Buffers.SIZEOF_FLOAT);
							gl.glEnableVertexAttribArray(attribLoc.intValue());
							vAttrOffset += vertexAttrSizes[index];
							if (DO_OUTPUT_ERRORS)
								outputErrors(ctx);

							if (OUTPUT_PER_FRAME_STATS)
								ctx.perFrameStats.glVertexAttribPointerUserAttribs++;

						}
					}
				}

				if ((vformat & GeometryArray.TEXTURE_COORDINATE) != 0)
				{
					boolean[] texSetsBound = new boolean[texCoordSetMapLen];
					for (int texUnit = 0; texUnit < numActiveTexUnitState && texUnit < texCoordSetMapLen; texUnit++)
					{
						int texSet = texCoordSetMap[texUnit];
						if (texSet != -1 && locs.glMultiTexCoord[texSet] != -1 && !texSetsBound[texSet])
						{
							texSetsBound[texSet] = true;
							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
							gl.glVertexAttribPointer(locs.glMultiTexCoord[texUnit], texSize, GL2ES2.GL_FLOAT, true, bstride,
									(startVertex + texCoordoff + texCoordSetMapOffset[texUnit]) * Buffers.SIZEOF_FLOAT);
							gl.glEnableVertexAttribArray(locs.glMultiTexCoord[texUnit]);
							if (DO_OUTPUT_ERRORS)
								outputErrors(ctx);

							if (OUTPUT_PER_FRAME_STATS)
								ctx.perFrameStats.enableTexCoordPointer++;

						}
					}

				}
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}

			//////////////////////////////////////////////

			if (geo_type == GeometryRetained.GEO_TYPE_TRI_STRIP_SET || geo_type == GeometryRetained.GEO_TYPE_TRI_FAN_SET
					|| geo_type == GeometryRetained.GEO_TYPE_LINE_STRIP_SET)
			{
				int primType = 0;

				// FIXME: GL_LINE and GL_LINE_STRIP simply go from one vertex to the next drawing a line between
				// each pair, what I want is a line between each set of 3 (that are not degenerate)
				if (ctx.polygonMode == PolygonAttributes.POLYGON_LINE)
					geo_type = GeometryRetained.GEO_TYPE_LINE_STRIP_SET;

				switch (geo_type)
				{
				case GeometryRetained.GEO_TYPE_TRI_STRIP_SET:
					primType = GL2ES2.GL_TRIANGLE_STRIP;
					break;
				case GeometryRetained.GEO_TYPE_TRI_FAN_SET:
					primType = GL2ES2.GL_TRIANGLE_FAN;
					break;
				case GeometryRetained.GEO_TYPE_LINE_STRIP_SET:
					primType = GL2ES2.GL_LINE_LOOP;
					break;
				}

				for (int i = 0; i < sarray.length; i++)
				{
					if (sarray[i] > 0)
					{
						gl.glDrawArrays(primType, start_array[i], sarray[i]);
						if (DO_OUTPUT_ERRORS)
							outputErrors(ctx);
						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glDrawStripArraysStrips++;
					}
				}
				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glDrawStripArrays++;
			}
			else
			{
				//need to override if polygonAttributes says so
				if (ctx.polygonMode == PolygonAttributes.POLYGON_LINE)
					geo_type = GeometryRetained.GEO_TYPE_LINE_SET;
				else if (ctx.polygonMode == PolygonAttributes.POLYGON_POINT)
					geo_type = GeometryRetained.GEO_TYPE_POINT_SET;

				switch (geo_type)
				{
				case GeometryRetained.GEO_TYPE_QUAD_SET:
					if(!quadArrayCautionPrinted) {
			    		System.err.println("QuadArray will render incorrectly, consider using TriangleArray. If you have the java3d-utils in the buildpath you can convert like this:");
			    		System.err.println("GeometryInfo gi = new GeometryInfo(quadArray);");
			    		System.err.println("gi.convertToIndexedTriangles();	");
			    		System.err.println("GeometryArray ga = gi.getIndexedGeometryArray(true, true, true, true, true);");
			    		quadArrayCautionPrinted = true;
			    	}
					//fallthrough
				case GeometryRetained.GEO_TYPE_TRI_SET:
					gl.glDrawArrays(GL2ES2.GL_TRIANGLES, 0, vcount);
					break;
				case GeometryRetained.GEO_TYPE_POINT_SET:
					gl.glDrawArrays(GL2ES2.GL_POINTS, 0, vcount);
					break;
				case GeometryRetained.GEO_TYPE_LINE_SET:
					gl.glDrawArrays(GL2ES2.GL_LINES, 0, vcount);
					break;
				}
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glDrawArrays++;
			}
		}
		else
		{
			if (!NO_PROGRAM_WARNING_GIVEN)
				System.err.println("Execute called with no shader Program in use!");
			NO_PROGRAM_WARNING_GIVEN = true;
			
			if (OUTPUT_PER_FRAME_STATS)
				ctx.perFrameStats.executeSkippedNoShaderProgram++;
		}

		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

	}

	//----------------------------------------------------------------------
	// Private helper methods for GeometryArrayRetained
	//

	private static String getVertexDescription(int vformat)
	{
		String res = "";
		if ((vformat & GeometryArray.COORDINATES) != 0)
			res += "COORDINATES ";
		if ((vformat & GeometryArray.NORMALS) != 0)
			res += "NORMALS ";
		if ((vformat & GeometryArray.COLOR) != 0)
			res += "COLOR ";
		if ((vformat & GeometryArray.WITH_ALPHA) != 0)
			res += "(WITH_ALPHA) ";
		if ((vformat & GeometryArray.TEXTURE_COORDINATE) != 0)
			res += "TEXTURE_COORDINATE ";
		if ((vformat & GeometryArray.TEXTURE_COORDINATE_2) != 0)
			res += "(2) ";
		if ((vformat & GeometryArray.TEXTURE_COORDINATE_3) != 0)
			res += "(3) ";
		if ((vformat & GeometryArray.TEXTURE_COORDINATE_4) != 0)
			res += "(4) ";
		if ((vformat & GeometryArray.VERTEX_ATTRIBUTES) != 0)
			res += "VERTEX_ATTRIBUTES ";
		return res;
	}

	private static String getGeometryDescription(int geo_type)
	{
		switch (geo_type)
		{
		case GeometryRetained.GEO_TYPE_TRI_STRIP_SET:
			return "GEO_TYPE_TRI_STRIP_SET";
		case GeometryRetained.GEO_TYPE_TRI_FAN_SET:
			return "GEO_TYPE_TRI_FAN_SET";
		case GeometryRetained.GEO_TYPE_LINE_STRIP_SET:
			return "GEO_TYPE_LINE_STRIP_SET";
		case GeometryRetained.GEO_TYPE_QUAD_SET:
			return "GEO_TYPE_QUAD_SET";
		case GeometryRetained.GEO_TYPE_TRI_SET:
			return "GEO_TYPE_TRI_SET";
		case GeometryRetained.GEO_TYPE_POINT_SET:
			return "GEO_TYPE_POINT_SET";
		case GeometryRetained.GEO_TYPE_LINE_SET:
			return "GEO_TYPE_LINE_SET";
		default:
			return "(unknown " + geo_type + ")";
		}
	}

	// used by GeometryArray by Reference with NIO buffer non indexed
	//texCoords will be an array of FloatBuffer
	@Override
	void executeVABuffer(Context ctx, GeometryArrayRetained geo, int geo_type, boolean isNonUniformScale, boolean ignoreVertexColors,
			int vcount, int vformat, int vdefined, int initialCoordIndex, Buffer vcoords, int initialColorIndex, Buffer cdataBuffer,
			float[] cfdata, byte[] cbdata, int initialNormalIndex, FloatBuffer ndata, int vertexAttrCount, int[] vertexAttrSizes,
			int[] vertexAttrIndices, FloatBuffer[] vertexAttrData, int texCoordMapLength, int[] texcoordoffset, int numActiveTexUnitState,
			int[] texIndex, int texstride, Object[] texCoords, int cdirty)
	{

		if (VERBOSE)
			System.err.println("JoglPipeline.executeVABuffer() ");

		boolean floatCoordDefined = ((vdefined & GeometryArrayRetained.COORD_FLOAT) != 0);
		boolean doubleCoordDefined = ((vdefined & GeometryArrayRetained.COORD_DOUBLE) != 0);
		boolean floatColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_FLOAT) != 0);
		boolean byteColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_BYTE) != 0);
		boolean normalsDefined = ((vdefined & GeometryArrayRetained.NORMAL_FLOAT) != 0);
		boolean vattrDefined = ((vdefined & GeometryArrayRetained.VATTR_FLOAT) != 0);
		boolean textureDefined = ((vdefined & GeometryArrayRetained.TEXCOORD_FLOAT) != 0);

		FloatBuffer fverts = null;
		DoubleBuffer dverts = null;
		FloatBuffer fclrs = null;
		ByteBuffer bclrs = null;

		FloatBuffer norms = null;
		FloatBuffer[] vertexAttrBufs = null;

		// Get vertex attribute arrays
		if (vattrDefined)
			vertexAttrBufs = vertexAttrData;

		// get coordinate array
		if (floatCoordDefined)
		{
			fverts = (FloatBuffer) vcoords;
		}
		else if (doubleCoordDefined)
		{
			// FIXME: doubles not supported for now
			throw new UnsupportedOperationException("Double coordinates in use.\n" + VALID_FORMAT_MESSAGE);
			// dverts = (DoubleBuffer) vcoords;
		}

		if (fverts == null && dverts == null)
		{
			return;
		}

		
		if (floatColorsDefined)
		{
				fclrs = (FloatBuffer) cdataBuffer;
		}

		if (byteColorsDefined)
		{
			// FIXME: bytes not supported for now
			throw new UnsupportedOperationException("byteColorsDefined.\n" + VALID_FORMAT_MESSAGE);
			// if (cbdata != null)
			// bclrs = getColorArrayBuffer(cbdata);
			// else
			// bclrs = (ByteBuffer) cdataBuffer;
		}

		// get normal array
		if (normalsDefined)
		{
			norms = ndata;
		}

		int[] sarray = null;
		int[] start_array = null;
		int strip_len = 0;
		if (geo_type == GeometryRetained.GEO_TYPE_TRI_STRIP_SET || geo_type == GeometryRetained.GEO_TYPE_TRI_FAN_SET
				|| geo_type == GeometryRetained.GEO_TYPE_LINE_STRIP_SET)
		{
			sarray = ((GeometryStripArrayRetained) geo).stripVertexCounts;
			strip_len = sarray.length;
			start_array = ((GeometryStripArrayRetained) geo).stripStartOffsetIndices;
		}

		executeGeometryArrayVA(ctx, geo, geo_type, isNonUniformScale, ignoreVertexColors, vcount, vformat, vdefined, initialCoordIndex,
				fverts, null, dverts, null, initialColorIndex, fclrs, cfdata, bclrs, null, initialNormalIndex, norms, null, vertexAttrCount,
				vertexAttrSizes, vertexAttrIndices, vertexAttrBufs, null, texCoordMapLength, texcoordoffset, numActiveTexUnitState,
				texIndex, texstride, texCoords, cdirty, sarray, strip_len, start_array);
	}

	// used by GeometryArray by Reference with java arrays non indexed
	//Object[] texCoords is an array of float arrays
	@Override
	void executeVA(Context ctx, GeometryArrayRetained geo, int geo_type, boolean isNonUniformScale, boolean ignoreVertexColors, int vcount,
			int vformat, int vdefined, int initialCoordIndex, float[] vfcoords, double[] vdcoords, int initialColorIndex, float[] cfdata,
			byte[] cbdata, int initialNormalIndex, float[] ndata, int vertexAttrCount, int[] vertexAttrSizes, int[] vertexAttrIndices,
			float[][] vertexAttrData, int texCoordMapLength, int[] texcoordoffset, int numActiveTexUnitState, int[] texIndex, int texstride,
			Object[] texCoords, int cdirty)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.executeVA()");

		boolean floatCoordDefined = ((vdefined & GeometryArrayRetained.COORD_FLOAT) != 0);
		boolean doubleCoordDefined = ((vdefined & GeometryArrayRetained.COORD_DOUBLE) != 0);
		boolean floatColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_FLOAT) != 0);
		boolean byteColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_BYTE) != 0);
		boolean normalsDefined = ((vdefined & GeometryArrayRetained.NORMAL_FLOAT) != 0);
		boolean vattrDefined = ((vdefined & GeometryArrayRetained.VATTR_FLOAT) != 0);
		boolean textureDefined = ((vdefined & GeometryArrayRetained.TEXCOORD_FLOAT) != 0);

		int[] sarray = null;
		int[] start_array = null;
		int strip_len = 0;
		if (geo_type == GeometryRetained.GEO_TYPE_TRI_STRIP_SET || geo_type == GeometryRetained.GEO_TYPE_TRI_FAN_SET
				|| geo_type == GeometryRetained.GEO_TYPE_LINE_STRIP_SET)
		{
			sarray = ((GeometryStripArrayRetained) geo).stripVertexCounts;
			strip_len = sarray.length;
			start_array = ((GeometryStripArrayRetained) geo).stripStartOffsetIndices;
		}

		if (doubleCoordDefined)
		{
			// FIXME: doubles not supported for now
			throw new UnsupportedOperationException("doubleCoordDefined.\n" + VALID_FORMAT_MESSAGE);
			// dverts = getVertexArrayBuffer(vdcoords);
		}

		if (byteColorsDefined)
		{
			// FIXME: byte colors not supported for now
			throw new UnsupportedOperationException("byteColorsDefined.\n" + VALID_FORMAT_MESSAGE);
			// bclrs = getColorArrayBuffer(cbdata);
		}

		executeGeometryArrayVA(ctx, geo, geo_type, isNonUniformScale, ignoreVertexColors, vcount, vformat, vdefined, initialCoordIndex,
				null, vfcoords, null, vdcoords, initialColorIndex, null, cfdata, null, cbdata, initialNormalIndex, null, ndata,
				vertexAttrCount, vertexAttrSizes, vertexAttrIndices, null, vertexAttrData, texCoordMapLength, texcoordoffset,
				numActiveTexUnitState, texIndex, texstride, texCoords, cdirty, sarray, strip_len, start_array);
	}

	private void executeGeometryArrayVA(Context absCtx, GeometryArrayRetained geo, int geo_type, boolean isNonUniformScale,
			boolean ignoreVertexColors, int vertexCount, int vformat, int vdefined, int initialCoordIndex, FloatBuffer fverts,
			float[] vfcoords, DoubleBuffer dverts, double[] vdcoords, int initialColorIndex, FloatBuffer fclrs, float[] cfdata,
			ByteBuffer bclrs, byte[] cbdata, int initialNormalIndex, FloatBuffer norms, float[] ndata, int vertexAttrCount,
			int[] vertexAttrSizes, int[] vertexAttrIndices, FloatBuffer[] vertexAttrBufs, float[][] vertexAttrData, int texCoordMapLength,
			int[] texCoordSetMap, int numActiveTexUnitState, int[] texindices, int texStride, Object[] texCoords, int cDirty, int[] sarray,
			int strip_len, int[] start_array)
	{
		Jogl2es2Context ctx = (Jogl2es2Context) absCtx;
		int shaderProgramId = ctx.shaderProgramId;

		if (shaderProgramId != -1)
		{
			GL2ES2 gl = ctx.gl2es2();
			ProgramData pd = ctx.programData;
			LocationData locs = pd.programToLocationData;

			setFFPAttributes(ctx, gl, shaderProgramId, pd, vdefined, ignoreVertexColors);

			// If any buffers need loading do that now
			GeometryData gd = loadAllBuffers(ctx, gl, geo, ignoreVertexColors, vertexCount, vformat, vdefined, fverts, vfcoords, dverts,
					vdcoords, fclrs, cfdata, bclrs, cbdata, norms, ndata, vertexAttrCount, vertexAttrSizes, vertexAttrBufs, vertexAttrData,
					texCoordMapLength, texCoordSetMap, texStride, texCoords);

			boolean floatCoordDefined = ((vdefined & GeometryArrayRetained.COORD_FLOAT) != 0);
			boolean doubleCoordDefined = ((vdefined & GeometryArrayRetained.COORD_DOUBLE) != 0);
			boolean floatColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_FLOAT) != 0);
			boolean byteColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_BYTE) != 0);
			boolean normalsDefined = ((vdefined & GeometryArrayRetained.NORMAL_FLOAT) != 0);
			boolean vattrDefined = ((vdefined & GeometryArrayRetained.VATTR_FLOAT) != 0);
			boolean textureDefined = ((vdefined & GeometryArrayRetained.TEXCOORD_FLOAT) != 0);

			// GeometryArray.ALLOW_REF_DATA_WRITE is just my indicator of changeability
			boolean morphable = geo.source.getCapability(GeometryArray.ALLOW_REF_DATA_WRITE)
					|| geo.source.getCapability(GeometryArray.ALLOW_COORDINATE_WRITE);

			// not required second time around for VAO (except morphable coords)
			boolean bindingRequired = true;
			// Note although we ask for ES2 we can get ES3, which demands a VAO or nothing renders
			if (gl.isGL2ES3())
			{
				GL2ES3 gl2es3 = (GL2ES3)gl;
				if (gd.vaoId == -1)
				{
					int[] tmp = new int[1];
					gl2es3.glGenVertexArrays(1, tmp, 0);
					gd.vaoId = tmp[0];
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);
				}
				else
				{
					bindingRequired = false;
				}
				gl2es3.glBindVertexArray(gd.vaoId);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}

			// Define the data pointers
			if (locs.glVertex != -1)
			{
				if (floatCoordDefined)
				{
					if (gd.geoToCoordBuf == -1)
					{
						new Throwable("Buffer load issue!").printStackTrace();
					}
					else
					{
						// rather than the morphable system above we should be able to use cDirty, but it appears to be wrong
						// if ((cDirty & GeometryArrayRetained.COORDINATE_CHANGED) != 0)
						if (morphable)
						{
							//if I have vfcoords instead of fverts need to get a fverts buff now
							if (vfcoords != null)
							{
								fverts = getVertexArrayBuffer(vfcoords);
							}

							int coordoff = 3 * initialCoordIndex;
							fverts.position(coordoff);
							// Sometime the FloatBuffer is swapped out for bigger or smaller 
							if (gd.geoToCoordBufSize != fverts.remaining())
							{
								System.err.println("Morphable buffer changed " + gd.geoToCoordBufSize + " != " + fverts.remaining()
										+ " un indexed ((GeometryArray) geo.source) " + ((GeometryArray) geo.source).getName() + " "
										+ geo.source + ", this is not nessasarily a problem");

								int prevBufId1 = gd.geoToCoordBuf1;// record these in order to delete after re-bind
								int prevBufId2 = gd.geoToCoordBuf2;

								int[] tmp = new int[2];
								gl.glGenBuffers(2, tmp, 0);
								gd.geoToCoordBuf = tmp[0];
								gd.geoToCoordBuf1 = tmp[0];
								gd.geoToCoordBuf2 = tmp[1];

								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf1);
								gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (fverts.remaining() * Float.SIZE / 8), fverts,
										GL2ES2.GL_DYNAMIC_DRAW);
								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf2);
								gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (fverts.remaining() * Float.SIZE / 8), fverts,
										GL2ES2.GL_DYNAMIC_DRAW);

								gd.geoToCoordBufSize = fverts.remaining();

								if (DO_OUTPUT_ERRORS)
									outputErrors(ctx);

								if (OUTPUT_PER_FRAME_STATS)
									ctx.perFrameStats.glBufferData++;

								//Notice no check for bindingRequired as we are altering the binding
								//and previously used buffer is deleted AFTER re-bind

								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
								gl.glVertexAttribPointer(locs.glVertex, 3, GL2ES2.GL_FLOAT, false, 0, 0);
								gl.glEnableVertexAttribArray(locs.glVertex);
								if (DO_OUTPUT_ERRORS)
									outputErrors(ctx);

								if (OUTPUT_PER_FRAME_STATS)
									ctx.perFrameStats.glVertexAttribPointerCoord++;

								if (OUTPUT_PER_FRAME_STATS)
									ctx.perFrameStats.coordCount += gd.geoToCoordBufSize;

								gl.glDeleteBuffers(1, new int[] { prevBufId1, prevBufId2 }, 0);
								if (DO_OUTPUT_ERRORS)
									outputErrors(ctx);
							}
							else
							{
								// work out the buffer to update and buffer to swap to
								if (gd.geoToCoordBuf == gd.geoToCoordBuf1)
								{
									// update 1 but set to draw 2
									gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf1);
									gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, (fverts.remaining() * Float.SIZE / 8), fverts);
									gd.geoToCoordBuf = gd.geoToCoordBuf2;
								}
								else
								{
									// update 2 but set to draw 1
									gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf2);
									gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, (fverts.remaining() * Float.SIZE / 8), fverts);
									gd.geoToCoordBuf = gd.geoToCoordBuf1;
								}

								if (DO_OUTPUT_ERRORS)
									outputErrors(ctx);

								if (OUTPUT_PER_FRAME_STATS)
									ctx.perFrameStats.glBufferSubData++;
							}
						}

					}

				}
				else if (doubleCoordDefined)
				{
					throw new UnsupportedOperationException("doubleCoordDefined.\n" + VALID_FORMAT_MESSAGE);
				}
				else
				{
					throw new UnsupportedOperationException("No coords defined.\n" + VALID_FORMAT_MESSAGE);
				}
			}
			else
			{
				throw new UnsupportedOperationException("Shader has no glVertex.\n" + VALID_FORMAT_MESSAGE);
			}

			// update other attributes if required
			if (floatColorsDefined && locs.glColor != -1 && !ignoreVertexColors)
			{
				// if ((cDirty & GeometryArrayRetained.COLOR_CHANGED) != 0)
				boolean changable = geo.source.getCapability(GeometryArray.ALLOW_COLOR_WRITE);
				if (changable)
				{
					//if I have cfdata instead of fclrs need to get a fclrs buff now
					if (cfdata != null)
					{
						fclrs = getColorArrayBuffer(cfdata);
					}

					fclrs.position(0);
					gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToColorBuf);
					gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, fclrs.remaining() * Float.SIZE / 8, fclrs);
				}
			}
			if (normalsDefined && locs.glNormal != -1)
			{
				// if ((cDirty & GeometryArrayRetained.NORMAL_CHANGED) != 0)
				boolean changable = geo.source.getCapability(GeometryArray.ALLOW_NORMAL_WRITE);
				if (changable)
				{
					//if I have ndata instead of norms need to get a norms buff now
					if (ndata != null)
					{
						norms = getNormalArrayBuffer(ndata);
					}
					norms.position(0);
					gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToNormalBuf);
					gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, norms.remaining() * Float.SIZE / 8, norms);
				}
			}

			if (vattrDefined)
			{
				if (vertexAttrData != null)
				{
					vertexAttrBufs = getVertexAttrSetBuffer(vertexAttrData);
				}
				for (int index = 0; index < vertexAttrCount; index++)
				{
					Integer attribLoc = locs.genAttIndexToLoc.get(index);
					if (attribLoc != null && attribLoc.intValue() != -1)
					{
						// if ((cDirty & GeometryArrayRetained.VATTR_CHANGED) != 0)
						boolean changable = geo.source.getCapability(GeometryArray.ALLOW_VERTEX_ATTR_WRITE);
						if (changable)
						{
							FloatBuffer vertexAttrs = vertexAttrBufs[index];
							vertexAttrs.position(0);
							SparseArray<Integer> bufIds = gd.geoToVertAttribBuf;
							Integer bufId = bufIds.get(index);
							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, bufId.intValue());
							gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, vertexAttrs.remaining() * Float.SIZE / 8, vertexAttrs);
						}
					}
				}
			}

			if (textureDefined)
			{
				// convert from float[][] to FloatBuffer[]
				//WOW careful java has a hard time knowing what an Object[] contains can't check float[][]
				if (!(texCoords[0] instanceof FloatBuffer))
				{
					texCoords = getTexCoordSetBuffer(texCoords);
				}
				boolean[] texSetsBound = new boolean[texCoords.length];
				for (int texUnit = 0; texUnit < numActiveTexUnitState && texUnit < texCoordMapLength; texUnit++)
				{
					int texSet = texCoordSetMap[texUnit];
					if (texSet != -1 && locs.glMultiTexCoord[texSet] != -1 && !texSetsBound[texSet])
					{
						boolean changable = geo.source.getCapability(GeometryArray.ALLOW_TEXCOORD_WRITE);
						if (changable)
						{
							FloatBuffer buf = (FloatBuffer) texCoords[texSet];
							buf.position(0);
							SparseArray<Integer> bufIds = gd.geoToTexCoordsBuf;
							Integer bufId = bufIds.get(texUnit);
							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, bufId.intValue());
							gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, buf.remaining() * Float.SIZE / 8, buf);
						}
					}
				}
			}

			// notice morphables must always rebind each frame as coord buffers are swapped
			if (bindingRequired || morphable)
			{
				// always do coords
				gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
				gl.glVertexAttribPointer(locs.glVertex, 3, GL2ES2.GL_FLOAT, false, 0, 0);
				gl.glEnableVertexAttribArray(locs.glVertex);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glVertexAttribPointerCoord++;
				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.coordCount += gd.geoToCoordBufSize;
			}

			if (bindingRequired)
			{
				if (floatColorsDefined && locs.glColor != -1 && !ignoreVertexColors)
				{
					if (gd.geoToColorBuf == -1)
					{
						new Throwable("Buffer load issue!").printStackTrace();
					}
					else
					{
						int coloroff;
						int sz = ((vformat & GeometryArray.WITH_ALPHA) != 0) ? 4 : 3;

						coloroff = sz * initialColorIndex;
						fclrs.position(coloroff);

						gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToColorBuf);
						gl.glVertexAttribPointer(locs.glColor, sz, GL2ES2.GL_FLOAT, false, 0, 0);
						gl.glEnableVertexAttribArray(locs.glColor);//must be called after Pointer above

						if (DO_OUTPUT_ERRORS)
							outputErrors(ctx);

						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glVertexAttribPointerColor++;
					}
				}
				else if (byteColorsDefined && locs.glColor != -1 && !ignoreVertexColors)
				{
					//FIXME: byteColors not supported for now, but I want them a lot
					throw new UnsupportedOperationException("byteColorsDefined.\n" + VALID_FORMAT_MESSAGE);
					/*int coloroff;
					int sz;
					if ((vformat & GeometryArray.WITH_ALPHA) != 0)
					{
						coloroff = 4 * initialColorIndex;
						sz = 4;
					}
					else
					{
						coloroff = 3 * initialColorIndex;
						sz = 3;
					}
					bclrs.position(coloroff);
					gl.glColorPointer(sz, GL2ES2.GL_UNSIGNED_BYTE, 0, bclrs);*/
				}
				else if (locs.glColor != -1)
				{
					// ignoreVertexcolors will have been set in FFP now as the glColors is unbound
					gl.glDisableVertexAttribArray(locs.glColor);
					if (OUTPUT_PER_FRAME_STATS)
						ctx.perFrameStats.glDisableVertexAttribArray++;
				}

				if (normalsDefined && locs.glNormal != -1)
				{
					if (gd.geoToNormalBuf == -1)
					{
						new Throwable("Buffer load issue!").printStackTrace();
					}
					else
					{
						gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToNormalBuf);
						gl.glVertexAttribPointer(locs.glNormal, 3, GL2ES2.GL_FLOAT, false, 0, 0);
						gl.glEnableVertexAttribArray(locs.glNormal);//must be called after Pointer above
						if (DO_OUTPUT_ERRORS)
							outputErrors(ctx);

						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glVertexAttribPointerNormals++;
					}
				}
				else
				{
					if (locs.glNormal != -1)
					{
						gl.glDisableVertexAttribArray(locs.glNormal);
						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glDisableVertexAttribArray++;
					}
				}

				if (vattrDefined)
				{
					for (int index = 0; index < vertexAttrCount; index++)
					{
						Integer attribLoc = locs.genAttIndexToLoc.get(index);
						if (attribLoc != null && attribLoc.intValue() != -1)
						{
							SparseArray<Integer> bufIds = gd.geoToVertAttribBuf;
							if (bufIds == null)
							{
								new Throwable("Buffer load issue!").printStackTrace();
							}

							Integer bufId = bufIds.get(index);
							if (bufId == null)
							{
								new Throwable("Buffer load issue!").printStackTrace();
							}
							else
							{
								int sz = vertexAttrSizes[index];

								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, bufId.intValue());
								gl.glVertexAttribPointer(attribLoc.intValue(), sz, GL2ES2.GL_FLOAT, false, 0, 0);
								gl.glEnableVertexAttribArray(attribLoc.intValue());//must be called after Pointer above
								if (DO_OUTPUT_ERRORS)
									outputErrors(ctx);

								if (OUTPUT_PER_FRAME_STATS)
									ctx.perFrameStats.glVertexAttribPointerUserAttribs++;
							}
						}
					}
				}

				if (textureDefined)
				{
					boolean[] texSetsBound = new boolean[texCoords.length];
					for (int texUnit = 0; texUnit < numActiveTexUnitState && texUnit < texCoordMapLength; texUnit++)
					{
						int texSet = texCoordSetMap[texUnit];
						if (texSet != -1 && locs.glMultiTexCoord[texSet] != -1 && !texSetsBound[texSet])
						{
							texSetsBound[texSet] = true;

							SparseArray<Integer> bufIds = gd.geoToTexCoordsBuf;
							if (bufIds == null)
							{
								new Throwable("Buffer load issue!").printStackTrace();
							}
							Integer bufId = bufIds.get(texUnit);
							if (bufId == null)
							{
								new Throwable("Buffer load issue!").printStackTrace();
							}
							else
							{
								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, bufId.intValue());
								gl.glVertexAttribPointer(locs.glMultiTexCoord[texUnit], texStride, GL2ES2.GL_FLOAT, true, 0, 0);
								gl.glEnableVertexAttribArray(locs.glMultiTexCoord[texUnit]);
								if (DO_OUTPUT_ERRORS)
									outputErrors(ctx);

								if (OUTPUT_PER_FRAME_STATS)
									ctx.perFrameStats.enableTexCoordPointer++;
							}
						}
					}

				}
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}

			if (geo_type == GeometryRetained.GEO_TYPE_TRI_STRIP_SET || geo_type == GeometryRetained.GEO_TYPE_TRI_FAN_SET
					|| geo_type == GeometryRetained.GEO_TYPE_LINE_STRIP_SET)
			{
				int primType = 0;

				// FIXME: GL_LINE and GL_LINE_STRIP simply go from one vertex to the next drawing a line between
				// each pair, what I want is a line between each set of 3 (that are not degenerate)
				if (ctx.polygonMode == PolygonAttributes.POLYGON_LINE)
					geo_type = GeometryRetained.GEO_TYPE_LINE_STRIP_SET;

				switch (geo_type)
				{
				case GeometryRetained.GEO_TYPE_TRI_STRIP_SET:
					primType = GL2ES2.GL_TRIANGLE_STRIP;
					break;
				case GeometryRetained.GEO_TYPE_TRI_FAN_SET:
					primType = GL2ES2.GL_TRIANGLE_FAN;
					break;
				case GeometryRetained.GEO_TYPE_LINE_STRIP_SET:
					primType = GL2ES2.GL_LINE_LOOP;
					break;
				}

				for (int i = 0; i < strip_len; i++)
				{
					if (sarray[i] > 0)
					{
						gl.glDrawArrays(primType, start_array[i], sarray[i]);
						if (DO_OUTPUT_ERRORS)
							outputErrors(ctx);
						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glDrawStripArraysStrips++;
					}
				}
				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glDrawStripArrays++;
			}
			else
			{
				// need to override if polygonAttributes says so
				if (ctx.polygonMode == PolygonAttributes.POLYGON_LINE)
					geo_type = GeometryRetained.GEO_TYPE_LINE_SET;
				else if (ctx.polygonMode == PolygonAttributes.POLYGON_POINT)
					geo_type = GeometryRetained.GEO_TYPE_POINT_SET;

				switch (geo_type)
				{
				case GeometryRetained.GEO_TYPE_QUAD_SET:
					if(!quadArrayCautionPrinted) {
			    		System.err.println("QuadArray will render incorrectly, consider using TriangleArray. If you have the java3d-utils in the buildpath you can convert like this:");
			    		System.err.println("GeometryInfo gi = new GeometryInfo(quadArray);");
			    		System.err.println("gi.convertToIndexedTriangles();	");
			    		System.err.println("GeometryArray ga = gi.getIndexedGeometryArray(true, true, true, true, true);");
			    		quadArrayCautionPrinted = true;
			    	}
					//fallthrough
				case GeometryRetained.GEO_TYPE_TRI_SET:
					gl.glDrawArrays(GL2ES2.GL_TRIANGLES, 0, vertexCount);
					break;
				case GeometryRetained.GEO_TYPE_POINT_SET:
					gl.glDrawArrays(GL2ES2.GL_POINTS, 0, vertexCount);
					break;
				case GeometryRetained.GEO_TYPE_LINE_SET:
					gl.glDrawArrays(GL2ES2.GL_LINES, 0, vertexCount);
					break;
				}
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glDrawArrays++;
			}
		}
		else
		{
			if (!NO_PROGRAM_WARNING_GIVEN)
				System.err.println("Execute called with no shader Program in use!");
			NO_PROGRAM_WARNING_GIVEN = true;
			
			if (OUTPUT_PER_FRAME_STATS)
				ctx.perFrameStats.executeSkippedNoShaderProgram++;
		}

		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

	}

	// ---------------------------------------------------------------------

	//
	// IndexedGeometryArrayRetained methods
	//

	// by-copy or interleaved, by reference, Java arrays
	@Override
	void executeIndexedGeometry(Context ctx, GeometryArrayRetained geo, int geo_type, boolean isNonUniformScale, boolean useAlpha,
			boolean ignoreVertexColors, int initialIndexIndex, int indexCount, int vertexCount, int vformat, int vertexAttrCount,
			int[] vertexAttrSizes, int texCoordSetCount, int[] texCoordSetMap, int texCoordSetMapLen, int[] texCoordSetOffset,
			int numActiveTexUnitState, float[] varray, float[] carray, int cdirty, int[] indexCoord)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.executeIndexedGeometry()");

		executeIndexedGeometryArray(ctx, geo, geo_type, isNonUniformScale, useAlpha, ignoreVertexColors, initialIndexIndex, indexCount,
				vertexCount, vformat, vertexAttrCount, vertexAttrSizes, texCoordSetCount, texCoordSetMap, texCoordSetMapLen,
				texCoordSetOffset, numActiveTexUnitState, varray, null, carray, cdirty, indexCoord);
	}

	// interleaved, by reference, nio buffer
	@Override
	void executeIndexedGeometryBuffer(Context ctx, GeometryArrayRetained geo, int geo_type, boolean isNonUniformScale, boolean useAlpha,
			boolean ignoreVertexColors, int initialIndexIndex, int indexCount, int vertexCount, int vformat, int texCoordSetCount,
			int[] texCoordSetMap, int texCoordSetMapLen, int[] texCoordSetOffset, int numActiveTexUnitState, FloatBuffer vdata,
			float[] carray, int cDirty, int[] indexCoord)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.executeIndexedGeometryBuffer()");

		executeIndexedGeometryArray(ctx, geo, geo_type, isNonUniformScale, useAlpha, ignoreVertexColors, initialIndexIndex, indexCount,
				vertexCount, vformat, 0, null, texCoordSetCount, texCoordSetMap, texCoordSetMapLen, texCoordSetOffset,
				numActiveTexUnitState, null, vdata, carray, cDirty, indexCoord);
	}

	//----------------------------------------------------------------------
	//
	// Helper routines for IndexedGeometryArrayRetained
	//

	private void executeIndexedGeometryArray(Context absCtx, GeometryArrayRetained geo, int geo_type, boolean isNonUniformScale,
			boolean useAlpha, boolean ignoreVertexColors, int initialIndexIndex, int indexCount, int vcount, int vformat,
			int vertexAttrCount, int[] vertexAttrSizes, int texCoordSetCount, int[] texCoordSetMap, int texCoordSetMapLen,
			int[] texCoordSetOffset, int numActiveTexUnitState, float[] varray, FloatBuffer vdata, float[] carray, int cDirty,
			int[] indexCoord)
	{

		if (VERBOSE)
			System.err.println("JoglPipeline.executeIndexedGeometryArray()");

		Jogl2es2Context ctx = (Jogl2es2Context) absCtx;
		int shaderProgramId = ctx.shaderProgramId;

		if (shaderProgramId != -1)
		{
			GL2ES2 gl = ctx.gl2es2();
			ProgramData pd = ctx.programData;
			LocationData locs = pd.programToLocationData;

			setFFPAttributes(ctx, gl, shaderProgramId, pd, vformat, ignoreVertexColors);

			int stride = 0, coordoff = 0, normoff = 0, coloroff = 0, texCoordoff = 0;
			int texSize = 0, texStride = 0;
			int vAttrOff = 0;
			int vAttrStride = 0;
			int bstride = 0, cbstride = 0;

			int[] sarray = null;
			int strip_len = 0;

			if (EXTRA_DEBUGGING)
			{
				System.err.println("Vertex format: " + getVertexDescription(vformat));
				System.err.println("Geometry type: " + getGeometryDescription(geo_type));
				if (carray != null)
				{
					System.err.println("  Separate color array");
				}
				else
				{
					System.err.println("  Colors (if any) interleaved");
				}
			}

			if ((vformat & GeometryArray.COORDINATES) != 0)
			{
				stride += 3;
			}
			if ((vformat & GeometryArray.NORMALS) != 0)
			{
				stride += 3;
				coordoff += 3;
			}

			if ((vformat & GeometryArray.COLOR) != 0)
			{
				if ((vformat & GeometryArray.WITH_ALPHA) != 0)
				{
					stride += 4;
					normoff += 4;
					coordoff += 4;
				}
				else
				{
					// Handle the case of executeInterleaved 3f
					stride += 3;
					normoff += 3;
					coordoff += 3;
				}
			}

			if ((vformat & GeometryArray.TEXTURE_COORDINATE) != 0)
			{
				if ((vformat & GeometryArray.TEXTURE_COORDINATE_2) != 0)
				{
					texSize = 2;
					texStride = 2 * texCoordSetCount;
				}
				else if ((vformat & GeometryArray.TEXTURE_COORDINATE_3) != 0)
				{
					texSize = 3;
					texStride = 3 * texCoordSetCount;
				}
				else if ((vformat & GeometryArray.TEXTURE_COORDINATE_4) != 0)
				{
					texSize = 4;
					texStride = 4 * texCoordSetCount;
				}
				stride += texStride;
				normoff += texStride;
				coloroff += texStride;
				coordoff += texStride;
			}

			if ((vformat & GeometryArray.VERTEX_ATTRIBUTES) != 0)
			{
				for (int i = 0; i < vertexAttrCount; i++)
				{
					vAttrStride += vertexAttrSizes[i];
				}
				stride += vAttrStride;
				normoff += vAttrStride;
				coloroff += vAttrStride;
				coordoff += vAttrStride;
				texCoordoff += vAttrStride;
			}

			bstride = stride * Buffers.SIZEOF_FLOAT;

			if (geo_type == GeometryRetained.GEO_TYPE_INDEXED_TRI_STRIP_SET || geo_type == GeometryRetained.GEO_TYPE_INDEXED_TRI_FAN_SET
					|| geo_type == GeometryRetained.GEO_TYPE_INDEXED_LINE_STRIP_SET)
			{
				sarray = ((IndexedGeometryStripArrayRetained) geo).stripIndexCounts;
				strip_len = sarray.length;
			}

			// using byRef interleaved array and has a separate pointer, then ..
			int cstride = stride;
			if (carray != null)
			{
				cstride = 4;
			}

			cbstride = cstride * Buffers.SIZEOF_FLOAT;

			if (EXTRA_DEBUGGING)
			{
				System.err.println("  initialIndexIndex: " + initialIndexIndex);
				System.err.println("  stride: " + stride);
				System.err.println("  bstride: " + bstride);
				System.err.println("  normoff: " + normoff);
				System.err.println("  coloroff: " + coloroff);
				System.err.println("  coordoff: " + coordoff);
				System.err.println("  texCoordoff: " + texCoordoff);
			}

			GeometryData gd = loadAllBuffers(ctx, gl, geo, ignoreVertexColors, vcount, vformat, vformat, vdata, varray, 0, vdata, carray,
					0);

			// GeometryArray.ALLOW_REF_DATA_WRITE is just my indicator of changeability
			boolean morphable = geo.source.getCapability(GeometryArray.ALLOW_REF_DATA_WRITE)
					|| geo.source.getCapability(GeometryArray.ALLOW_COORDINATE_WRITE);

			// not required second time around for VAO (except morphable coords)
			boolean bindingRequired = true;
			// Note although we ask for ES2 we can get ES3, which demands a VAO or nothing renders
			if (gl.isGL2ES3())
			{
				GL2ES3 gl2es3 = (GL2ES3)gl;
				if (gd.vaoId == -1)
				{
					int[] tmp = new int[1];
					gl2es3.glGenVertexArrays(1, tmp, 0);
					gd.vaoId = tmp[0];
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);
				}
				else
				{
					bindingRequired = false;
				}
				gl2es3.glBindVertexArray(gd.vaoId);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}
			// refill the buffers in case of writeable data
			if (locs.glVertex != -1)
			{

				if (gd.geoToCoordBuf == -1)
				{
					new Throwable("Buffer load issue!").printStackTrace();
				}
				else
				{
					// if ((cDirty & GeometryArrayRetained.COORDINATE_CHANGED) != 0)
					if (morphable)
					{
						FloatBuffer verts = null;

						// do we need to covert a float[]
						if (varray != null)
						{
							verts = getVertexArrayBuffer(varray);
						}
						else
						{
							verts = vdata;
						}

						verts.position(0);
						// Sometime the FloatBuffer is swapped out for bigger or smaller 
						if (gd.geoToCoordBufSize != verts.remaining())
						{
							System.err.println("Morphable buffer changed " + gd.geoToCoordBufSize + " != " + verts.remaining()
									+ " un indexed ((GeometryArray) geo.source) " + ((GeometryArray) geo.source).getName() + " "
									+ geo.source + ", this is not nessasarily a problem");

							int prevBufId1 = gd.geoToCoordBuf1;// record these in order to delete after re-bind
							int prevBufId2 = gd.geoToCoordBuf2;

							int[] tmp = new int[2];
							gl.glGenBuffers(2, tmp, 0);
							gd.geoToCoordBuf = tmp[0];
							gd.geoToCoordBuf1 = tmp[0];
							gd.geoToCoordBuf2 = tmp[1];

							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf1);
							gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (verts.remaining() * Float.SIZE / 8), verts, GL2ES2.GL_DYNAMIC_DRAW);
							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf2);
							gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (verts.remaining() * Float.SIZE / 8), verts, GL2ES2.GL_DYNAMIC_DRAW);

							gd.geoToCoordBufSize = verts.remaining();

							if (DO_OUTPUT_ERRORS)
								outputErrors(ctx);

							if (OUTPUT_PER_FRAME_STATS)
								ctx.perFrameStats.glBufferData++;

							//Notice no check for bindingRequired as we are altering the binding
							//and previously used buffer is deleted AFTER re-bind

							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
							gl.glVertexAttribPointer(locs.glVertex, 3, GL2ES2.GL_FLOAT, false, 0, 0);
							gl.glEnableVertexAttribArray(locs.glVertex);
							if (DO_OUTPUT_ERRORS)
								outputErrors(ctx);

							if (OUTPUT_PER_FRAME_STATS)
								ctx.perFrameStats.glVertexAttribPointerCoord++;

							if (OUTPUT_PER_FRAME_STATS)
								ctx.perFrameStats.coordCount += gd.geoToCoordBufSize;

							gl.glDeleteBuffers(1, new int[] { prevBufId1, prevBufId2 }, 0);
							if (DO_OUTPUT_ERRORS)
								outputErrors(ctx);
						}
						else
						{
							// work out the buffer to update and buffer to swap to
							if (gd.geoToCoordBuf == gd.geoToCoordBuf1)
							{
								// update 1 but set to draw 2
								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf1);
								gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, (verts.remaining() * Float.SIZE / 8), verts);
								gd.geoToCoordBuf = gd.geoToCoordBuf2;
							}
							else
							{
								// update 2 but set to draw 1
								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf2);
								gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, (verts.remaining() * Float.SIZE / 8), verts);
								gd.geoToCoordBuf = gd.geoToCoordBuf1;
							}

							if (DO_OUTPUT_ERRORS)
								outputErrors(ctx);

							if (OUTPUT_PER_FRAME_STATS)
								ctx.perFrameStats.glBufferSubData++;
						}
					}

				}

			}
			else
			{
				throw new UnsupportedOperationException("Shader has no glVertex.\n" + VALID_FORMAT_MESSAGE);
			}

			// update other attributes if required
			if (((vformat & GeometryArray.COLOR) != 0) && locs.glColor != -1 && !ignoreVertexColors)
			{
				// if ((cDirty & GeometryArrayRetained.COLOR_CHANGED) != 0)
				boolean changable = geo.source.getCapability(GeometryArray.ALLOW_COLOR_WRITE);
				if (changable)
				{
					// clrs in the vdata, unless seperate
					FloatBuffer clrs = null;
					FloatBuffer verts = null;

					// do we need to covert a float[]
					if (varray != null)
					{
						verts = getVertexArrayBuffer(varray);
					}
					else
					{
						verts = vdata;
					}
					if (carray != null)
					{
						clrs = getColorArrayBuffer(carray);
					}
					else
					{
						clrs = verts;
					}
					clrs.position(0);
					gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToColorBuf);
					gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, clrs.remaining() * Float.SIZE / 8, clrs);
				}
			}

			// notice morphables must always rebind each frame as coord buffers are swapped
			if (bindingRequired || morphable)
			{
				// always do coords 
				gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
				gl.glVertexAttribPointer(locs.glVertex, 3, GL2ES2.GL_FLOAT, false, bstride, coordoff * Buffers.SIZEOF_FLOAT);
				gl.glEnableVertexAttribArray(locs.glVertex);

				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glVertexAttribPointerCoord++;
				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.coordCount += gd.geoToCoordBufSize;
			}

			if (bindingRequired)
			{
				if (((vformat & GeometryArray.COLOR) != 0) && locs.glColor != -1 && !ignoreVertexColors)
				{
					if (gd.geoToColorBuf == -1)
					{
						new Throwable("Buffer load issue!").printStackTrace();
					}
					else
					{
						int sz = ((vformat & GeometryArray.WITH_ALPHA) != 0) ? 4 : 3;

						gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToColorBuf);
						gl.glVertexAttribPointer(locs.glColor, sz, GL2ES2.GL_FLOAT, false, cbstride, coloroff * Buffers.SIZEOF_FLOAT);
						gl.glEnableVertexAttribArray(locs.glColor);
						if (DO_OUTPUT_ERRORS)
							outputErrors(ctx);

						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glVertexAttribPointerColor++;
					}
				}
				else if (locs.glColor != -1)
				{
					// ignoreVertexcolors will have been set in FFP now as the glColors is unbound
					gl.glDisableVertexAttribArray(locs.glColor);
					if (OUTPUT_PER_FRAME_STATS)
						ctx.perFrameStats.glDisableVertexAttribArray++;
				}

				if (((vformat & GeometryArray.NORMALS) != 0) && locs.glNormal != -1)
				{
					if (gd.geoToCoordBuf == -1)
					{
						new Throwable("Buffer load issue!").printStackTrace();
					}
					else
					{
						gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
						gl.glVertexAttribPointer(locs.glNormal, 3, GL2ES2.GL_FLOAT, false, bstride, normoff * Buffers.SIZEOF_FLOAT);
						gl.glEnableVertexAttribArray(locs.glNormal);
						if (DO_OUTPUT_ERRORS)
							outputErrors(ctx);

						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glVertexAttribPointerNormals++;
					}
				}
				else
				{
					if (locs.glNormal != -1)
					{
						gl.glDisableVertexAttribArray(locs.glNormal);
						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glDisableVertexAttribArray++;
					}
				}

				if ((vformat & GeometryArray.VERTEX_ATTRIBUTES) != 0)
				{

					int vAttrOffset = vAttrOff;
					for (int index = 0; index < vertexAttrCount; index++)
					{
						Integer attribLoc = locs.genAttIndexToLoc.get(index);
						if (attribLoc != null && attribLoc.intValue() != -1)
						{
							int sz = vertexAttrSizes[index];

							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
							gl.glVertexAttribPointer(attribLoc.intValue(), sz, GL2ES2.GL_FLOAT, false, bstride,
									vAttrOffset * Buffers.SIZEOF_FLOAT);
							gl.glEnableVertexAttribArray(attribLoc.intValue());
							vAttrOffset += vertexAttrSizes[index];
							if (DO_OUTPUT_ERRORS)
								outputErrors(ctx);

							if (OUTPUT_PER_FRAME_STATS)
								ctx.perFrameStats.glVertexAttribPointerUserAttribs++;

						}
					}
				}

				if ((vformat & GeometryArray.TEXTURE_COORDINATE) != 0)
				{
					boolean[] texSetsBound = new boolean[texCoordSetMapLen];
					for (int texUnit = 0; texUnit < numActiveTexUnitState && texUnit < texCoordSetMapLen; texUnit++)
					{
						int texSet = texCoordSetMap[texUnit];
						if (texSet != -1 && locs.glMultiTexCoord[texSet] != -1 && !texSetsBound[texSet])
						{
							texSetsBound[texSet] = true;
							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
							gl.glVertexAttribPointer(locs.glMultiTexCoord[texUnit], texSize, GL2ES2.GL_FLOAT, true, bstride,
									texCoordoff * Buffers.SIZEOF_FLOAT);
							gl.glEnableVertexAttribArray(locs.glMultiTexCoord[texUnit]);
							if (DO_OUTPUT_ERRORS)
								outputErrors(ctx);

							if (OUTPUT_PER_FRAME_STATS)
								ctx.perFrameStats.enableTexCoordPointer++;

						}
					}

				}
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}

			//////////////////////////////////////////////
			if (geo_type == GeometryRetained.GEO_TYPE_INDEXED_TRI_STRIP_SET || geo_type == GeometryRetained.GEO_TYPE_INDEXED_TRI_FAN_SET
					|| geo_type == GeometryRetained.GEO_TYPE_INDEXED_LINE_STRIP_SET)
			{
				int primType = 0;

				//FIXME: GL_LINE and GL_LINE_STRIP simply go from one vertex to the next drawing a line between
				// each pair, what I want is a line between each set of 3 (that are not jumpers)

				if (ctx.polygonMode == PolygonAttributes.POLYGON_LINE)
					geo_type = GeometryRetained.GEO_TYPE_INDEXED_LINE_STRIP_SET;

				switch (geo_type)
				{
				case GeometryRetained.GEO_TYPE_INDEXED_TRI_STRIP_SET:
					primType = GL2ES2.GL_TRIANGLE_STRIP;
					break;
				case GeometryRetained.GEO_TYPE_INDEXED_TRI_FAN_SET:
					primType = GL2ES2.GL_TRIANGLE_FAN;
					break;
				case GeometryRetained.GEO_TYPE_INDEXED_LINE_STRIP_SET:
					primType = GL2ES2.GL_LINE_LOOP;
					break;
				}

				int[] stripInd = gd.geoToIndStripBuf;
				// if no index buffers build build them now
				if (stripInd == null)
				{
					stripInd = new int[strip_len];
					gl.glGenBuffers(strip_len, stripInd, 0);

					int offset = initialIndexIndex;
					/*ShortBuffer indicesBuffer = ByteBuffer.allocateDirect(indexCoord.length * 2).order(ByteOrder.nativeOrder())
							.asShortBuffer();
					for (int s = 0; s < indexCoord.length; s++)
						indicesBuffer.put(s, (short) indexCoord[s]);*/
					
					ShortBuffer indicesBuffer = getIndexArrayBuffer(indexCoord);
					for (int i = 0; i < strip_len; i++)
					{
						indicesBuffer.position(offset);
						int count = sarray[i];
						int indBufId = stripInd[i];

						gl.glBindBuffer(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, indBufId);
						gl.glBufferData(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, count * Short.SIZE / 8, indicesBuffer, GL2ES2.GL_STATIC_DRAW);
						if (DO_OUTPUT_ERRORS)
							outputErrors(ctx);
						offset += count;

						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glBufferData++;
					}

					gd.geoToIndStripBuf = stripInd;
				}

				for (int i = 0; i < strip_len; i++)
				{
					int count = sarray[i];
					int indBufId = stripInd[i];

					//type Specifies the type of the values in indices. Must be
					// GL_UNSIGNED_BYTE or GL_UNSIGNED_SHORT.    
					// Apparently ES3 has included this guy now, so I'm a bit commited to it
					//https://www.khronos.org/opengles/sdk/docs/man/xhtml/glDrawElements.xml
					//This restriction is relaxed when GL_OES_element_index_uint is supported. 
					//GL_UNSIGNED_INT

					gl.glBindBuffer(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, indBufId);
					gl.glDrawElements(primType, count, GL2ES2.GL_UNSIGNED_SHORT, 0);
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);

					if (OUTPUT_PER_FRAME_STATS)
						ctx.perFrameStats.glDrawStripElementsStrips++;

				}
				// gl.glBindBuffer(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, 0);

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glDrawStripElements++;

				// note only the first count so multi strips is worng here,
				// but...
				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.indexCount += gd.geoToIndBufSize;

			}
			else
			{

				// bind my indexes ready for the draw call
				if (gd.geoToIndBuf == -1)
				{
					// create and fill index buffer
					//ShortBuffer indBuf = ByteBuffer.allocateDirect(indexCoord.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
					//for (int s = 0; s < indexCoord.length; s++)
					//	indBuf.put(s, (short) indexCoord[s]);
					//indBuf.position(initialIndexIndex);
					ShortBuffer indBuf = getIndexArrayBuffer(indexCoord);

					int[] tmp = new int[1];
					gl.glGenBuffers(1, tmp, 0);
					gd.geoToIndBuf = tmp[0];// about to add to map below
					gl.glBindBuffer(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, gd.geoToIndBuf);
					gl.glBufferData(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, indBuf.remaining() * Short.SIZE / 8, indBuf, GL2ES2.GL_STATIC_DRAW);
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);

					gd.geoToIndBufSize = indBuf.remaining();

				}

				gl.glBindBuffer(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, gd.geoToIndBuf);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.indexCount += gd.geoToIndBufSize;

				// Need to override if polygonAttributes says we should be drawing lines
				// Note these are not poly line just contiguous lines between each pair of points
				// So it looks really rubbish
				if (ctx.polygonMode == PolygonAttributes.POLYGON_LINE)
					geo_type = GeometryRetained.GEO_TYPE_INDEXED_LINE_SET;
				else if (ctx.polygonMode == PolygonAttributes.POLYGON_POINT)
					geo_type = GeometryRetained.GEO_TYPE_INDEXED_POINT_SET;

				switch (geo_type)
				{
				case GeometryRetained.GEO_TYPE_INDEXED_QUAD_SET:
					if(!quadArrayCautionPrinted) {
			    		System.err.println("QuadArray will render incorrectly, consider using TriangleArray. If you have the java3d-utils in the buildpath you can convert like this:");
			    		System.err.println("GeometryInfo gi = new GeometryInfo(quadArray);");
			    		System.err.println("gi.convertToIndexedTriangles();	");
			    		System.err.println("GeometryArray ga = gi.getIndexedGeometryArray(true, true, true, true, true);");
			    		quadArrayCautionPrinted = true;
			    	}
					//fallthrough
				case GeometryRetained.GEO_TYPE_INDEXED_TRI_SET:
					gl.glDrawElements(GL2ES2.GL_TRIANGLES, indexCount, GL2ES2.GL_UNSIGNED_SHORT, 0);
					break;
				case GeometryRetained.GEO_TYPE_INDEXED_POINT_SET:
					gl.glDrawElements(GL2ES2.GL_POINTS, indexCount, GL2ES2.GL_UNSIGNED_SHORT, 0);
					break;
				case GeometryRetained.GEO_TYPE_INDEXED_LINE_SET:
					gl.glDrawElements(GL2ES2.GL_LINES, indexCount, GL2ES2.GL_UNSIGNED_SHORT, 0);
					break;
				}
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glDrawElements++;
			}
		}
		else
		{
			if (!NO_PROGRAM_WARNING_GIVEN)
				System.err.println("Execute called with no shader Program in use!");
			NO_PROGRAM_WARNING_GIVEN = true;
			
			if (OUTPUT_PER_FRAME_STATS)
				ctx.perFrameStats.executeSkippedNoShaderProgram++;
		}

		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

	}

	// non interleaved, by reference, Java arrays

	@Override
	void executeIndexedGeometryVA(Context ctx, GeometryArrayRetained geo, int geo_type, boolean isNonUniformScale,
			boolean ignoreVertexColors, int initialIndexIndex, int validIndexCount, int vertexCount, int vformat, int vdefined,
			float[] vfcoords, double[] vdcoords, float[] cfdata, byte[] cbdata, float[] ndata, int vertexAttrCount, int[] vertexAttrSizes,
			float[][] vertexAttrData, int texCoordMapLength, int[] texcoordoffset, int numActiveTexUnitState, int texStride,
			Object[] texCoords, int cdirty, int[] indexCoord)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.executeIndexedGeometryVA()");

		boolean floatCoordDefined = ((vdefined & GeometryArrayRetained.COORD_FLOAT) != 0);
		boolean doubleCoordDefined = ((vdefined & GeometryArrayRetained.COORD_DOUBLE) != 0);
		boolean floatColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_FLOAT) != 0);
		boolean byteColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_BYTE) != 0);
		boolean normalsDefined = ((vdefined & GeometryArrayRetained.NORMAL_FLOAT) != 0);
		boolean vattrDefined = ((vdefined & GeometryArrayRetained.VATTR_FLOAT) != 0);
		boolean textureDefined = ((vdefined & GeometryArrayRetained.TEXCOORD_FLOAT) != 0);

		int[] sarray = null;
		int strip_len = 0;
		if (geo_type == GeometryRetained.GEO_TYPE_INDEXED_TRI_STRIP_SET || geo_type == GeometryRetained.GEO_TYPE_INDEXED_TRI_FAN_SET
				|| geo_type == GeometryRetained.GEO_TYPE_INDEXED_LINE_STRIP_SET)
		{
			sarray = ((IndexedGeometryStripArrayRetained) geo).stripIndexCounts;
			strip_len = sarray.length;
		}

		if (doubleCoordDefined)
		{
			// FIXME: doubles not supported for now
			throw new UnsupportedOperationException("doubleCoordDefined.\n" + VALID_FORMAT_MESSAGE);
			// dverts = getVertexArrayBuffer(vdcoords);
		}

		if (byteColorsDefined)
		{
			// FIXME: byte colors not supported for now
			throw new UnsupportedOperationException("byteColorsDefined.\n" + VALID_FORMAT_MESSAGE);
			// bclrs = getColorArrayBuffer(cbdata);
		}

		executeIndexedGeometryArrayVA(ctx, geo, geo_type, isNonUniformScale, ignoreVertexColors, initialIndexIndex, validIndexCount,
				vertexCount, vformat, vdefined, null, vfcoords, null, vdcoords, null, cfdata, null, cbdata, null, ndata, vertexAttrCount,
				vertexAttrSizes, null, vertexAttrData, texCoordMapLength, texcoordoffset, numActiveTexUnitState, texStride, texCoords,
				cdirty, indexCoord, sarray, strip_len);
	}

	// non interleaved, by reference, nio buffer

	@Override
	void executeIndexedGeometryVABuffer(Context ctx, GeometryArrayRetained geo, int geo_type, boolean isNonUniformScale,
			boolean ignoreVertexColors, int initialIndexIndex, int validIndexCount, int vertexCount, int vformat, int vdefined,
			Buffer vcoords, Buffer cdataBuffer, float[] cfdata, byte[] cbdata, FloatBuffer ndata, int vertexAttrCount,
			int[] vertexAttrSizes, FloatBuffer[] vertexAttrData, int texCoordMapLength, int[] texcoordoffset, int numActiveTexUnitState,
			int texStride, Object[] texCoords, int cdirty, int[] indexCoord)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.executeIndexedGeometryVABuffer() ");

		boolean floatCoordDefined = ((vdefined & GeometryArrayRetained.COORD_FLOAT) != 0);
		boolean doubleCoordDefined = ((vdefined & GeometryArrayRetained.COORD_DOUBLE) != 0);
		boolean floatColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_FLOAT) != 0);
		boolean byteColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_BYTE) != 0);
		boolean normalsDefined = ((vdefined & GeometryArrayRetained.NORMAL_FLOAT) != 0);
		boolean vattrDefined = ((vdefined & GeometryArrayRetained.VATTR_FLOAT) != 0);
		boolean textureDefined = ((vdefined & GeometryArrayRetained.TEXCOORD_FLOAT) != 0);

		FloatBuffer fverts = null;
		DoubleBuffer dverts = null;
		FloatBuffer fclrs = null;
		ByteBuffer bclrs = null;

		FloatBuffer norms = null;
		FloatBuffer[] vertexAttrBufs = null;

		// Get vertex attribute arrays
		if (vattrDefined)
		{
			vertexAttrBufs = vertexAttrData;
		}

		// get coordinate array
		if (floatCoordDefined)
		{
			fverts = (FloatBuffer) vcoords;
		}
		else if (doubleCoordDefined)
		{
			// FIXME: doubles not supported for now
			throw new UnsupportedOperationException("doubleCoordDefined.\n" + VALID_FORMAT_MESSAGE);
			// dverts = (DoubleBuffer) vcoords;
		}

		if (fverts == null && dverts == null)
		{
			return;
		}

		int[] sarray = null;
		int strip_len = 0;
		if (geo_type == GeometryRetained.GEO_TYPE_INDEXED_TRI_STRIP_SET || geo_type == GeometryRetained.GEO_TYPE_INDEXED_TRI_FAN_SET
				|| geo_type == GeometryRetained.GEO_TYPE_INDEXED_LINE_STRIP_SET)
		{
			sarray = ((IndexedGeometryStripArrayRetained) geo).stripIndexCounts;
			strip_len = sarray.length;
		}

		// get color array
		if (floatColorsDefined)
		{
				fclrs = (FloatBuffer) cdataBuffer;
		}
		else if (byteColorsDefined)
		{
			// FIXME:  not supported for now
			throw new UnsupportedOperationException("byteColorsDefined.\n" + VALID_FORMAT_MESSAGE);

			// if (cbdata != null)
			// bclrs = getColorArrayBuffer(cbdata);
			// else
			// bclrs = (ByteBuffer) cdataBuffer;
		}

		// get normal array
		if (normalsDefined)
		{
			norms = ndata;
		}

		executeIndexedGeometryArrayVA(ctx, geo, geo_type, isNonUniformScale, ignoreVertexColors, initialIndexIndex, validIndexCount,
				vertexCount, vformat, vdefined, fverts, null, dverts, null, fclrs, cfdata, bclrs, null, norms, null, vertexAttrCount,
				vertexAttrSizes, vertexAttrBufs, null, texCoordMapLength, texcoordoffset, numActiveTexUnitState, texStride, texCoords,
				cdirty, indexCoord, sarray, strip_len);
	}

	// ----------------------------------------------------------------------
	//
	// Helper routines for IndexedGeometryArrayRetained
	//
	// careful - isNonUniformScale is always false regardless
	private void executeIndexedGeometryArrayVA(Context absCtx, GeometryArrayRetained geo, int geo_type, boolean isNonUniformScale,
			boolean ignoreVertexColors, int initialIndexIndex, int validIndexCount, int vertexCount, int vformat, int vdefined,
			FloatBuffer fverts, float[] vfarray, DoubleBuffer dverts, double[] vdarray, FloatBuffer fclrs, float[] cfarray,
			ByteBuffer bclrs, byte[] cbarray, FloatBuffer norms, float[] fnorms, int vertexAttrCount, int[] vertexAttrSizes,
			FloatBuffer[] vertexAttrBufs, float[][] vertexAttrArrays, int texCoordMapLength, int[] texCoordSetMap,
			int numActiveTexUnitState, int texStride, Object[] texCoords, int cDirty, int[] indexCoord, int[] sarray, int strip_len)
	{

		// removed if (ATTEMPT_OPTIMIZED_VERTICES &&
		// executeIndexedGeometryOptimized(...

		Jogl2es2Context ctx = (Jogl2es2Context) absCtx;
		int shaderProgramId = ctx.shaderProgramId;

		if (shaderProgramId != -1)
		{
			GL2ES2 gl = ctx.gl2es2();
			ProgramData pd = ctx.programData;
			LocationData locs = pd.programToLocationData;

			setFFPAttributes(ctx, gl, shaderProgramId, pd, vdefined, ignoreVertexColors);

			// If any buffers need loading do that now 
			GeometryData gd = loadAllBuffers(ctx, gl, geo, ignoreVertexColors, vertexCount, vformat, vdefined, fverts, vfarray, dverts,
					vdarray, fclrs, cfarray, bclrs, cbarray, norms, fnorms, vertexAttrCount, vertexAttrSizes, vertexAttrBufs,
					vertexAttrArrays, texCoordMapLength, texCoordSetMap, texStride, texCoords);

			boolean floatCoordDefined = ((vdefined & GeometryArrayRetained.COORD_FLOAT) != 0);
			boolean doubleCoordDefined = ((vdefined & GeometryArrayRetained.COORD_DOUBLE) != 0);
			boolean floatColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_FLOAT) != 0);
			boolean byteColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_BYTE) != 0);
			boolean normalsDefined = ((vdefined & GeometryArrayRetained.NORMAL_FLOAT) != 0);
			boolean vattrDefined = ((vdefined & GeometryArrayRetained.VATTR_FLOAT) != 0);
			boolean textureDefined = ((vdefined & GeometryArrayRetained.TEXCOORD_FLOAT) != 0);

			// GeometryArray.ALLOW_REF_DATA_WRITE is just my indicator of changeability
			boolean morphable = geo.source.getCapability(GeometryArray.ALLOW_REF_DATA_WRITE)
					|| geo.source.getCapability(GeometryArray.ALLOW_COORDINATE_WRITE);

			// not required second time around for VAO (except morphable coords)
			boolean bindingRequired = true;
			// Note although we ask for ES2 we can get ES3, which demands a VAO or nothing renders
			if (gl.isGL2ES3())
			{
				GL2ES3 gl2es3 = (GL2ES3)gl;
				if (gd.vaoId == -1)
				{
					int[] tmp = new int[1];
					gl2es3.glGenVertexArrays(1, tmp, 0);
					gd.vaoId = tmp[0];
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);
				}
				else
				{
					bindingRequired = false;
				}
				gl2es3.glBindVertexArray(gd.vaoId);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}

			// Define the data pointers
			if (locs.glVertex != -1)
			{
				if (floatCoordDefined)
				{
					// TODO: Building of buffers etc and index buffers should really take place not on the j3d thread if possible
					if (gd.geoToCoordBuf == -1)
					{
						new Throwable("Buffer load issue!").printStackTrace();
					}
					else
					{
						if (morphable)
						{

							//if I have vfcoords instead of fverts need to get a fverts buff now
							if (vfarray != null)
							{
								fverts = getVertexArrayBuffer(vfarray);
							}

							fverts.position(0);

							// Sometime the FloatBuffer is swapped out for bigger or smaller 
							if (gd.geoToCoordBufSize != fverts.remaining())
							{
								System.err.println("Morphable buffer changed " + gd.geoToCoordBufSize + " != " + fverts.remaining()
										+ " ((GeometryArray) geo.source) " + ((GeometryArray) geo.source).getName() + " " + geo.source);

								int prevBufId1 = gd.geoToCoordBuf1;// record these in order to delete below
								int prevBufId2 = gd.geoToCoordBuf2;

								int[] tmp = new int[2];
								gl.glGenBuffers(2, tmp, 0);
								gd.geoToCoordBuf = tmp[0];
								gd.geoToCoordBuf1 = tmp[0];
								gd.geoToCoordBuf2 = tmp[1];

								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf1);
								gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (fverts.remaining() * Float.SIZE / 8), fverts,
										GL2ES2.GL_STATIC_DRAW);

								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf2);
								gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (fverts.remaining() * Float.SIZE / 8), fverts,
										GL2ES2.GL_STATIC_DRAW);

								gd.geoToCoordBufSize = fverts.remaining();

								if (DO_OUTPUT_ERRORS)
									outputErrors(ctx);

								if (OUTPUT_PER_FRAME_STATS)
									ctx.perFrameStats.glBufferData++;

								// Notice no check for bindingRequired as we are altering the binding
								// and previously used buffer is deleted AFTER re-bind

								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
								gl.glVertexAttribPointer(locs.glVertex, 3, GL2ES2.GL_FLOAT, false, 0, 0);
								gl.glEnableVertexAttribArray(locs.glVertex);
								if (DO_OUTPUT_ERRORS)
									outputErrors(ctx);

								if (OUTPUT_PER_FRAME_STATS)
									ctx.perFrameStats.glVertexAttribPointerCoord++;

								if (OUTPUT_PER_FRAME_STATS)
									ctx.perFrameStats.coordCount += gd.geoToCoordBufSize;

								gl.glDeleteBuffers(1, new int[] { prevBufId1, prevBufId2 }, 0);
								if (DO_OUTPUT_ERRORS)
									outputErrors(ctx);
							}
							else
							{
								// work out the buffer to update and buffer to swap to
								if (gd.geoToCoordBuf == gd.geoToCoordBuf1)
								{
									// update 1 but set to draw 2
									gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf1);
									gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, (fverts.remaining() * Float.SIZE / 8), fverts);
									gd.geoToCoordBuf = gd.geoToCoordBuf2;

								}
								else
								{
									// update 2 but set to draw 1
									gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf2);
									gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, (fverts.remaining() * Float.SIZE / 8), fverts);
									gd.geoToCoordBuf = gd.geoToCoordBuf1;
								}

								if (DO_OUTPUT_ERRORS)
									outputErrors(ctx);

								if (OUTPUT_PER_FRAME_STATS)
									ctx.perFrameStats.glBufferSubData++;
							}
						}

					}
				}
				else if (doubleCoordDefined)
				{
					throw new UnsupportedOperationException("doubleCoordDefined.\n" + VALID_FORMAT_MESSAGE);
				}
				else
				{
					throw new UnsupportedOperationException("No coords defined.\n" + VALID_FORMAT_MESSAGE);
				}
			}
			else
			{
				throw new UnsupportedOperationException("Shader has no glVertex.\n" + VALID_FORMAT_MESSAGE);
			}

			// update other attributes if required
			if (floatColorsDefined && locs.glColor != -1 && !ignoreVertexColors)
			{
				// if ((cDirty & GeometryArrayRetained.COLOR_CHANGED) != 0)
				boolean changable = geo.source.getCapability(GeometryArray.ALLOW_COLOR_WRITE);
				if (changable)
				{
					//if I have cfdata instead of fclrs need to get a fclrs buff now
					if (cfarray != null)
					{
						fclrs = getColorArrayBuffer(cfarray);
					}

					fclrs.position(0);
					gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToColorBuf);
					gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, fclrs.remaining() * Float.SIZE / 8, fclrs);
				}
			}
			if (normalsDefined && locs.glNormal != -1)
			{
				// if ((cDirty & GeometryArrayRetained.NORMAL_CHANGED) != 0)
				boolean changable = geo.source.getCapability(GeometryArray.ALLOW_NORMAL_WRITE);
				if (changable)
				{
					//if I have ndata instead of norms need to get a norms buff now
					if (fnorms != null)
					{
						norms = getNormalArrayBuffer(fnorms);
					}

					norms.position(0);
					gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToNormalBuf);
					gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, norms.remaining() * Float.SIZE / 8, norms);
				}
			}

			if (vattrDefined)
			{
				if (vertexAttrArrays != null)
				{
					vertexAttrBufs = getVertexAttrSetBuffer(vertexAttrArrays);
				}

				for (int index = 0; index < vertexAttrCount; index++)
				{
					Integer attribLoc = locs.genAttIndexToLoc.get(index);
					if (attribLoc != null && attribLoc.intValue() != -1)
					{
						// if ((cDirty & GeometryArrayRetained.VATTR_CHANGED) != 0)
						boolean changable = geo.source.getCapability(GeometryArray.ALLOW_VERTEX_ATTR_WRITE);
						if (changable)
						{
							FloatBuffer vertexAttrs = vertexAttrBufs[index];
							vertexAttrs.position(0);
							SparseArray<Integer> bufIds = gd.geoToVertAttribBuf;
							Integer bufId = bufIds.get(index);
							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, bufId.intValue());
							gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, vertexAttrs.remaining() * Float.SIZE / 8, vertexAttrs);
						}
					}
				}
			}

			if (textureDefined)
			{
				// convert from float[][] to FloatBuffer[]
				//WOW careful java has a hard time knowing what an Object[] contains can't check float[][]
				if (!(texCoords[0] instanceof FloatBuffer))
				{
					texCoords = getTexCoordSetBuffer(texCoords);
				}
				boolean[] texSetsBound = new boolean[texCoords.length];
				for (int texUnit = 0; texUnit < numActiveTexUnitState && texUnit < texCoordMapLength; texUnit++)
				{
					int texSet = texCoordSetMap[texUnit];
					if (texSet != -1 && locs.glMultiTexCoord[texSet] != -1 && !texSetsBound[texSet])
					{
						boolean changable = geo.source.getCapability(GeometryArray.ALLOW_TEXCOORD_WRITE);
						if (changable)
						{
							FloatBuffer buf = (FloatBuffer) texCoords[texSet];
							buf.position(0);
							SparseArray<Integer> bufIds = gd.geoToTexCoordsBuf;
							Integer bufId = bufIds.get(texUnit);
							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, bufId.intValue());
							gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, buf.remaining() * Float.SIZE / 8, buf);
						}
					}
				}
			}

			// binding is required for morphables coords as always swappping buffers each second frame
			if (bindingRequired || morphable)
			{
				// always coords
				gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
				gl.glVertexAttribPointer(locs.glVertex, 3, GL2ES2.GL_FLOAT, false, 0, 0);
				gl.glEnableVertexAttribArray(locs.glVertex);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glVertexAttribPointerCoord++;

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.coordCount += gd.geoToCoordBufSize;

			}

			if (bindingRequired)
			{
				if (floatColorsDefined && locs.glColor != -1 && !ignoreVertexColors)
				{
					if (gd.geoToColorBuf == -1)
					{
						new Throwable("Buffer load issue!").printStackTrace();
					}
					else
					{
						int sz = ((vformat & GeometryArray.WITH_ALPHA) != 0) ? 4 : 3;
						gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToColorBuf);

						gl.glVertexAttribPointer(locs.glColor, sz, GL2ES2.GL_FLOAT, true, 0, 0);
						gl.glEnableVertexAttribArray(locs.glColor);
						if (DO_OUTPUT_ERRORS)
							outputErrors(ctx);

						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glVertexAttribPointerColor++;
					}

				}
				else if (byteColorsDefined && locs.glColor != -1 && !ignoreVertexColors)
				{
					//FIXME: byteColors not supported for now
					throw new UnsupportedOperationException("byteColorsDefined.\n" + VALID_FORMAT_MESSAGE);

					/*bclrs.position(0);
					if ((vformat & GeometryArray.WITH_ALPHA) != 0)
					{
						gl.glColorPointer(4, GL2ES2.GL_UNSIGNED_BYTE, 0, bclrs);
					}
					else
					{
						gl.glColorPointer(3, GL2ES2.GL_UNSIGNED_BYTE, 0, bclrs);
					}*/
				}
				else if (locs.glColor != -1)
				{
					// ignoreVertexcolors will be set in FFP now as the glColors is unbound
					gl.glDisableVertexAttribArray(locs.glColor);
					if (OUTPUT_PER_FRAME_STATS)
						ctx.perFrameStats.glDisableVertexAttribArray++;
				}

				if (normalsDefined)
				{
					if (locs.glNormal != -1)
					{
						if (gd.geoToNormalBuf == -1)
						{
							new Throwable("Buffer load issue!").printStackTrace();
						}
						else
						{
							gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToNormalBuf);

							gl.glVertexAttribPointer(locs.glNormal, 3, GL2ES2.GL_FLOAT, true, 0, 0);
							gl.glEnableVertexAttribArray(locs.glNormal);//must be called after Pointer above
							if (DO_OUTPUT_ERRORS)
								outputErrors(ctx);

							if (OUTPUT_PER_FRAME_STATS)
								ctx.perFrameStats.glVertexAttribPointerNormals++;
						}
					}
				}
				else
				{
					if (locs.glNormal != -1)
					{
						gl.glDisableVertexAttribArray(locs.glNormal);
						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glDisableVertexAttribArray++;
					}
				}

				if (vattrDefined)
				{
					for (int index = 0; index < vertexAttrCount; index++)
					{
						Integer attribLoc = locs.genAttIndexToLoc.get(index);
						if (attribLoc != null && attribLoc.intValue() != -1)
						{
							SparseArray<Integer> bufIds = gd.geoToVertAttribBuf;
							if (bufIds == null)
							{
								new Throwable("Buffer load issue!").printStackTrace();
							}

							Integer bufId = bufIds.get(index);
							if (bufId == null)
							{
								new Throwable("Buffer load issue!").printStackTrace();
							}
							else
							{
								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, bufId.intValue());

								int sz = vertexAttrSizes[index];

								gl.glVertexAttribPointer(attribLoc.intValue(), sz, GL2ES2.GL_FLOAT, false, 0, 0);
								gl.glEnableVertexAttribArray(attribLoc.intValue());//must be called after Pointer above
								if (DO_OUTPUT_ERRORS)
									outputErrors(ctx);

								if (OUTPUT_PER_FRAME_STATS)
									ctx.perFrameStats.glVertexAttribPointerUserAttribs++;
							}
						}
					}
				}

				if (textureDefined)
				{
					boolean[] texSetsBound = new boolean[texCoords.length];
					for (int texUnit = 0; texUnit < numActiveTexUnitState && texUnit < texCoordMapLength; texUnit++)
					{
						int texSet = texCoordSetMap[texUnit];
						if (texSet != -1 && locs.glMultiTexCoord[texSet] != -1 && !texSetsBound[texSet])
						{
							texSetsBound[texSet] = true;
							// stupid interface...
							FloatBuffer buf = (FloatBuffer) texCoords[texSet];
							buf.position(0);

							SparseArray<Integer> bufIds = gd.geoToTexCoordsBuf;
							if (bufIds == null)
							{
								new Throwable("Buffer load issue!").printStackTrace();
							}
							Integer bufId = bufIds.get(texUnit);
							if (bufId == null)
							{
								new Throwable("Buffer load issue!").printStackTrace();
							}
							else
							{
								gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, bufId.intValue());
								gl.glVertexAttribPointer(locs.glMultiTexCoord[texUnit], texStride, GL2ES2.GL_FLOAT, true, 0, 0);
								gl.glEnableVertexAttribArray(locs.glMultiTexCoord[texUnit]);// must be called after Pointer above
								if (DO_OUTPUT_ERRORS)
									outputErrors(ctx);

								if (OUTPUT_PER_FRAME_STATS)
									ctx.perFrameStats.enableTexCoordPointer++;
							}
						}
					}
				}

				// general catch all
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}

			if (geo_type == GeometryRetained.GEO_TYPE_INDEXED_TRI_STRIP_SET || geo_type == GeometryRetained.GEO_TYPE_INDEXED_TRI_FAN_SET
					|| geo_type == GeometryRetained.GEO_TYPE_INDEXED_LINE_STRIP_SET)
			{
				int primType = 0;

				// need to override if polygonAttributes says so
				if (ctx.polygonMode == PolygonAttributes.POLYGON_LINE)
					geo_type = GeometryRetained.GEO_TYPE_INDEXED_LINE_STRIP_SET;

				switch (geo_type)
				{
				case GeometryRetained.GEO_TYPE_INDEXED_TRI_STRIP_SET:
					primType = GL2ES2.GL_TRIANGLE_STRIP;
					break;
				case GeometryRetained.GEO_TYPE_INDEXED_TRI_FAN_SET:
					primType = GL2ES2.GL_TRIANGLE_FAN;
					break;
				case GeometryRetained.GEO_TYPE_INDEXED_LINE_STRIP_SET:
					primType = GL2ES2.GL_LINES;
					break;
				}

				int[] stripInd = gd.geoToIndStripBuf;
				// if no index buffers build build them now
				if (stripInd == null)
				{
					stripInd = new int[strip_len];
					gl.glGenBuffers(strip_len, stripInd, 0);

					int offset = initialIndexIndex;
					/*ShortBuffer indicesBuffer = ByteBuffer.allocateDirect(indexCoord.length * 2).order(ByteOrder.nativeOrder())
							.asShortBuffer();
					for (int s = 0; s < indexCoord.length; s++)
						indicesBuffer.put(s, (short) indexCoord[s]);*/
					
					ShortBuffer indicesBuffer = getIndexArrayBuffer(indexCoord);
					
					for (int i = 0; i < strip_len; i++)
					{
						indicesBuffer.position(offset);
						int count = sarray[i];
						int indBufId = stripInd[i];

						gl.glBindBuffer(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, indBufId);
						gl.glBufferData(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, count * Short.SIZE / 8, indicesBuffer, GL2ES2.GL_STATIC_DRAW);
						if (DO_OUTPUT_ERRORS)
							outputErrors(ctx);
						offset += count;

						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glBufferData++;
					}

					gd.geoToIndStripBuf = stripInd;
				}
				else
				{
					//a good cDirty and a DYNAMIC_DRAW call needed
					/*if ((cDirty & GeometryArrayRetained.INDEX_CHANGED) != 0)
					{
						int offset = initialIndexIndex;
						IntBuffer indicesBuffer = IntBuffer.wrap(indexCoord);
						for (int i = 0; i < strip_len; i++)
						{
							indicesBuffer.position(offset);
							int count = sarray[i];
							int indBufId = stripInd[i];
					
							gl.glBindBuffer(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, indBufId);
							gl.glBufferSubData(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, 0, count * Integer.SIZE / 8, indicesBuffer);
							//gl.glBindBuffer(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, 0);
							offset += count;
					}*/
				}

				for (int i = 0; i < strip_len; i++)
				{
					int count = sarray[i];
					int indBufId = stripInd[i];

					// type Specifies the type of the values in indices. Must be
					// GL_UNSIGNED_BYTE or GL_UNSIGNED_SHORT.    
					// Apparently ES3 has included this GL_UNSIGNED_INT
					// https://www.khronos.org/opengles/sdk/docs/man/xhtml/glDrawElements.xml
					// This restriction is relaxed when GL_OES_element_index_uint is supported. 
					// GL_UNSIGNED_INT

					gl.glBindBuffer(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, indBufId);
					gl.glDrawElements(primType, count, GL2ES2.GL_UNSIGNED_SHORT, 0);
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);

					if (OUTPUT_PER_FRAME_STATS)
						ctx.perFrameStats.glDrawStripElementsStrips++;

				}

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glDrawStripElements++;

				// note only the first count so multi-strips is wrong here, but...
				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.indexCount += gd.geoToIndBufSize;

			}
			else
			{
				// bind my indexes ready for the draw call
				if (gd.geoToIndBuf == -1)
				{
					// create and fill index buffer
					/*ShortBuffer indBuf = ByteBuffer.allocateDirect(indexCoord.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
					for (int s = 0; s < indexCoord.length; s++)
						indBuf.put(s, (short) indexCoord[s]);
					indBuf.position(initialIndexIndex);*/
					
					ShortBuffer indBuf = getIndexArrayBuffer(indexCoord);

					int[] tmp = new int[1];
					gl.glGenBuffers(1, tmp, 0);
					gd.geoToIndBuf = tmp[0];// about to add to map below
					gl.glBindBuffer(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, gd.geoToIndBuf);
					gl.glBufferData(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, indBuf.remaining() * Short.SIZE / 8, indBuf, GL2ES2.GL_STATIC_DRAW);
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);

					gd.geoToIndBufSize = indBuf.remaining();

				}
				else
				{
					//a good cDirty and a DYNAMIC_DRAW call needed
					/*if ((cDirty & GeometryArrayRetained.INDEX_CHANGED) != 0)
					{
						IntBuffer indBuf = IntBuffer.wrap(indexCoord);
						indBuf.position(initialIndexIndex);
						gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, indexBufId.intValue());
						gl.glBufferSubData(GL2ES2.GL_ARRAY_BUFFER, 0, indBuf.remaining() * Integer.SIZE / 8, indBuf);
					}*/
				}

				gl.glBindBuffer(GL2ES2.GL_ELEMENT_ARRAY_BUFFER, gd.geoToIndBuf);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.indexCount += gd.geoToIndBufSize;

				// Need to override if polygonAttributes says we should be drawing lines
				// Note these are not poly line just contiguous lines between each pair of points
				// So it looks really rubbish
				if (ctx.polygonMode == PolygonAttributes.POLYGON_LINE)
					geo_type = GeometryRetained.GEO_TYPE_INDEXED_LINE_SET;
				else if (ctx.polygonMode == PolygonAttributes.POLYGON_POINT)
					geo_type = GeometryRetained.GEO_TYPE_INDEXED_POINT_SET;

				switch (geo_type)
				{
				case GeometryRetained.GEO_TYPE_INDEXED_QUAD_SET:
					if(!quadArrayCautionPrinted) {
			    		System.err.println("QuadArray will render incorrectly, consider using TriangleArray. If you have the java3d-utils in the buildpath you can convert like this:");
			    		System.err.println("GeometryInfo gi = new GeometryInfo(quadArray);");
			    		System.err.println("gi.convertToIndexedTriangles();	");
			    		System.err.println("GeometryArray ga = gi.getIndexedGeometryArray(true, true, true, true, true);");
			    		quadArrayCautionPrinted = true;
			    	}
					//fallthrough
				case GeometryRetained.GEO_TYPE_INDEXED_TRI_SET:
					gl.glDrawElements(GL2ES2.GL_TRIANGLES, validIndexCount, GL2ES2.GL_UNSIGNED_SHORT, 0);
					break;
				case GeometryRetained.GEO_TYPE_INDEXED_POINT_SET:
					gl.glDrawElements(GL2ES2.GL_POINTS, validIndexCount, GL2ES2.GL_UNSIGNED_SHORT, 0);
					break;
				case GeometryRetained.GEO_TYPE_INDEXED_LINE_SET:
					gl.glDrawElements(GL2ES2.GL_LINES, validIndexCount, GL2ES2.GL_UNSIGNED_SHORT, 0);
					break;
				}
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glDrawElements++;

			}
		}
		else
		{
			if (!NO_PROGRAM_WARNING_GIVEN)
				System.err.println("Execute called with no shader Program in use!");
			NO_PROGRAM_WARNING_GIVEN = true;
			
			if (OUTPUT_PER_FRAME_STATS)
				ctx.perFrameStats.executeSkippedNoShaderProgram++;
		}
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
	}

	/**
	 * Over time we have had things recorded and in FFP they are considered current state
	 * in programmable we have to push them across manually each time recorded in JoglesContext
	 * @param gl
	 * @param vdefined
	 */

	private static void setFFPAttributes(Jogl2es2Context ctx, GL2ES2 gl, int shaderProgramId, ProgramData pd, int vdefined, boolean ignoreVertexColors)
	{

		LocationData locs = pd.programToLocationData;

		//boolean floatCoordDefined = ((vdefined & GeometryArrayRetained.COORD_FLOAT) != 0);
		//boolean doubleCoordDefined = ((vdefined & GeometryArrayRetained.COORD_DOUBLE) != 0);
		boolean floatColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_FLOAT) != 0);
		boolean byteColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_BYTE) != 0);
		//boolean normalsDefined = ((vdefined & GeometryArrayRetained.NORMAL_FLOAT) != 0);
		//boolean vattrDefined = ((vdefined & GeometryArrayRetained.VATTR_FLOAT) != 0);
		//boolean textureDefined = ((vdefined & GeometryArrayRetained.TEXCOORD_FLOAT) != 0);

		if (OUTPUT_PER_FRAME_STATS)
			ctx.perFrameStats.setFFPAttributes++;

		boolean isGL2ES3 = gl.isGL2ES3();
		// if shader hasn't changed location of uniform I don't need to reset these (they are cleared to -1 at the start of each swap)
		if (locs.glProjectionMatrix != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP || (shaderProgramId != ctx.prevShaderProgram))
			{
				if (isGL2ES3)
					gl.glUniformMatrix4fv(locs.glProjectionMatrix, 1, true, ctx.matrixUtil.toArray(ctx.currentProjMat), 0);
				else
					gl.glUniformMatrix4fv(locs.glProjectionMatrix, 1, false,
							Jogl2es2MatrixUtil.transposeInPlace(ctx.matrixUtil.toArray(ctx.currentProjMat)), 0);

				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}
		}
		if (locs.glProjectionMatrixInverse != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP || (shaderProgramId != ctx.prevShaderProgram))
			{
				// EXPENSIVE!!!!! only calc if asked for, and even then...
				try
				{
					ctx.currentProjMatInverse.set(ctx.currentProjMat);
					ctx.matrixUtil.invert(ctx.currentProjMatInverse);
				}
				catch (SingularMatrixException e)
				{
					System.err.println("" + e);
				}

				if (isGL2ES3)
					gl.glUniformMatrix4fv(locs.glProjectionMatrixInverse, 1, true, ctx.matrixUtil.toArray(ctx.currentProjMatInverse), 0);
				else
					gl.glUniformMatrix4fv(locs.glProjectionMatrixInverse, 1, false,
							Jogl2es2MatrixUtil.transposeInPlace(ctx.matrixUtil.toArray(ctx.currentProjMatInverse)), 0);

				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}
		}
		if (locs.glViewMatrix != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP || (shaderProgramId != ctx.prevShaderProgram))
			{
				gl.glUniformMatrix4fv(locs.glViewMatrix, 1, true, ctx.matrixUtil.toArray(ctx.currentViewMat), 0);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}
		}

		if (locs.glModelMatrix != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP
					|| (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.modelMatrix.m00 == Double.NEGATIVE_INFINITY))
			{

				if (isGL2ES3)
					gl.glUniformMatrix4fv(locs.glModelMatrix, 1, true, ctx.matrixUtil.toArray(ctx.currentModelMat), 0);
				else
					gl.glUniformMatrix4fv(locs.glModelMatrix, 1, false,
							Jogl2es2MatrixUtil.transposeInPlace(ctx.matrixUtil.toArray(ctx.currentModelMat)), 0);

				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_FFP)
					ctx.gl_state.modelMatrix.m00 = 0;

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.modelMatrixUpdated++;
			}
			else if (OUTPUT_PER_FRAME_STATS)
			{
				ctx.perFrameStats.modelMatrixSkipped++;
			}
		}

		if (locs.glModelViewMatrix != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP
					|| (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.glModelViewMatrix.m00 == Double.NEGATIVE_INFINITY))
			{
				// Expensive, only calc if required, not in the setmodelview call, in case unneeded
				if (ctx.currentModelViewMat.m00 == Double.NEGATIVE_INFINITY)
				ctx.currentModelViewMat.mul(ctx.currentViewMat, ctx.currentModelMat);
	
				if (isGL2ES3)
					gl.glUniformMatrix4fv(locs.glModelViewMatrix, 1, true, ctx.matrixUtil.toArray(ctx.currentModelViewMat), 0);
				else
					gl.glUniformMatrix4fv(locs.glModelViewMatrix, 1, false,
							Jogl2es2MatrixUtil.transposeInPlace(ctx.matrixUtil.toArray(ctx.currentModelViewMat)), 0);

				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
	
				if (MINIMISE_NATIVE_CALLS_FFP)
					ctx.gl_state.glModelViewMatrix.m00 = 0;
				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glModelViewMatrixUpdated++;
			}
			else if (OUTPUT_PER_FRAME_STATS)
			{
				ctx.perFrameStats.glModelViewMatrixSkipped++;
			}
		}
		if (locs.glModelViewMatrixInverse != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP
					|| (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.glModelViewMatrixInverse.m00 == Double.NEGATIVE_INFINITY))
			{
				// Expensive, only calc if required, not in the setmodelview call, in case unneeded
				if (ctx.currentModelViewMatInverse.m00 == Double.NEGATIVE_INFINITY)
				{
				ctx.currentModelViewMatInverse.mul(ctx.currentViewMat, ctx.currentModelMat);
				ctx.matrixUtil.invert(ctx.currentModelViewMatInverse);
				}
	
				
				if (isGL2ES3)
					gl.glUniformMatrix4fv(locs.glModelViewMatrixInverse, 1, true, ctx.matrixUtil.toArray(ctx.currentModelViewMatInverse), 0);
				else
					gl.glUniformMatrix4fv(locs.glModelViewMatrixInverse, 1, false,
							Jogl2es2MatrixUtil.transposeInPlace(ctx.matrixUtil.toArray(ctx.currentModelViewMatInverse)), 0);

				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
	
				if (MINIMISE_NATIVE_CALLS_FFP)
					ctx.gl_state.glModelViewMatrixInverse.m00 = 0;
				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glModelViewMatrixInverseUpdated++;
			}
			else if (OUTPUT_PER_FRAME_STATS)
			{
				ctx.perFrameStats.glModelViewMatrixInverseSkipped++;
			}
		}

		if (locs.glModelViewProjectionMatrix != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP || (shaderProgramId != ctx.prevShaderProgram
					|| ctx.gl_state.glModelViewProjectionMatrix.m00 == Double.NEGATIVE_INFINITY))
			{
				// Expensive, only calc if required, not in the setmodelview call, in case unneeded
				if (ctx.currentModelViewMat.m00 == Double.NEGATIVE_INFINITY)
				ctx.currentModelViewMat.mul(ctx.currentViewMat, ctx.currentModelMat);
				if (ctx.currentModelViewProjMat.m00 == Double.NEGATIVE_INFINITY)
				ctx.currentModelViewProjMat.mul(ctx.currentProjMat, ctx.currentModelViewMat);
	
				if (isGL2ES3)
					gl.glUniformMatrix4fv(locs.glModelViewProjectionMatrix, 1, true, ctx.matrixUtil.toArray(ctx.currentModelViewProjMat), 0);
				else
					gl.glUniformMatrix4fv(locs.glModelViewProjectionMatrix, 1, false,
							Jogl2es2MatrixUtil.transposeInPlace(ctx.matrixUtil.toArray(ctx.currentModelViewProjMat)), 0);
				
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
	
				if (MINIMISE_NATIVE_CALLS_FFP)
					ctx.gl_state.glModelViewProjectionMatrix.m00 = 0;

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glModelViewProjectionMatrixUpdated++;
			}
			else if (OUTPUT_PER_FRAME_STATS)
			{
				ctx.perFrameStats.glModelViewProjectionMatrixSkipped++;
			}
		}

		if (locs.glNormalMatrix != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP
					|| (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.glNormalMatrix.m00 == Double.NEGATIVE_INFINITY))
			{
				// Expensive, only calc if required, not in the setmodelview call, in case unneeded
				if (ctx.currentModelViewMat.m00 == Double.NEGATIVE_INFINITY)
				ctx.currentModelViewMat.mul(ctx.currentViewMat, ctx.currentModelMat);
				if (ctx.currentNormalMat.m00 == Double.NEGATIVE_INFINITY)
				Jogl2es2MatrixUtil.transposeInvert(ctx.currentModelViewMat, ctx.currentNormalMat);
	
				if (isGL2ES3)
					gl.glUniformMatrix3fv(locs.glNormalMatrix, 1, true, ctx.matrixUtil.toArray(ctx.currentNormalMat), 0);
				else
					gl.glUniformMatrix3fv(locs.glNormalMatrix, 1, false,
							Jogl2es2MatrixUtil.transposeInPlace(ctx.matrixUtil.toArray(ctx.currentNormalMat)), 0);
				
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_FFP)
					ctx.gl_state.glNormalMatrix.m00 = 0;

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glNormalMatrixUpdated++;
			}
			else if (OUTPUT_PER_FRAME_STATS)
			{
				ctx.perFrameStats.glNormalMatrixSkipped++;
			}
		}

		// if set one of the 2 colors below should be used by the shader (material for lighting)

		if (locs.ignoreVertexColors != -1)
		{
			// vertex colors MUST be ignored if no glColors set
			boolean finalIgnoreVertexColors = (!floatColorsDefined && !byteColorsDefined) || ctx.renderingData.ignoreVertexColors == 1;
		
			//TODO: the execute calls all have a separate ignore vertex colors bool, but it appears to add no value?
			// is it just a legacy artifact of some sort?
			/*if(finalIgnoreVertexColors != ignoreVertexColors )
			{			
				System.out.println("odd mismatch I wonder about why ");
				System.out.println("finalIgnoreVertexColors " +finalIgnoreVertexColors+ " ignoreVertexColors " +ignoreVertexColors);
				System.out.println("ctx.renderingData.ignoreVertexColors " +ctx.renderingData.ignoreVertexColors);
				System.out.println("floatColorsDefined " +floatColorsDefined);
			}*/
			
			int finalIgnoreVertexColorsInt = finalIgnoreVertexColors ? 1 : 0;

			//note ctx.gl_state.ignoreVertexColors can be -1 for not set
			if (!MINIMISE_NATIVE_CALLS_FFP || (shaderProgramId != ctx.prevShaderProgram
					|| ctx.gl_state.ignoreVertexColors != finalIgnoreVertexColorsInt))
			{
				gl.glUniform1i(locs.ignoreVertexColors, finalIgnoreVertexColorsInt);// note local variable used

				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_FFP)
					ctx.gl_state.ignoreVertexColors = finalIgnoreVertexColorsInt;
			}
		}

		// the front material structure
		if (locs.glFrontMaterial.present)
		{
			//note != not equals
			if (locs.glFrontMaterial.lightEnabled != -1 && (shaderProgramId != ctx.prevShaderProgram
					|| ctx.gl_state.glFrontMaterial.lightEnabled != ctx.materialData.lightEnabled))
			{
					gl.glUniform1i(locs.glFrontMaterial.lightEnabled, ctx.materialData.lightEnabled);
				ctx.gl_state.glFrontMaterial.lightEnabled = ctx.materialData.lightEnabled;
			}

			if (locs.glFrontMaterial.ambient != -1
					&& (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.glFrontMaterial.ambient.x == Float.NEGATIVE_INFINITY))
			{
					gl.glUniform4f(locs.glFrontMaterial.ambient, ctx.materialData.ambient.x, ctx.materialData.ambient.y,
							ctx.materialData.ambient.z, 1f);
				ctx.gl_state.glFrontMaterial.ambient.x = 0;
			}
			if (locs.glFrontMaterial.diffuse != -1
					&& (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.glFrontMaterial.diffuse.x == Float.NEGATIVE_INFINITY))
			{
					gl.glUniform4f(locs.glFrontMaterial.diffuse, ctx.materialData.diffuse.x, ctx.materialData.diffuse.y,
							ctx.materialData.diffuse.z, ctx.materialData.diffuse.w);
				ctx.gl_state.glFrontMaterial.diffuse.x = 0;
			}
			if (locs.glFrontMaterial.emission != -1
					&& (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.glFrontMaterial.emission.x == Float.NEGATIVE_INFINITY))
			{
					gl.glUniform4f(locs.glFrontMaterial.emission, ctx.materialData.emission.x, ctx.materialData.emission.y,
							ctx.materialData.emission.z, 1f); // note extra alpha value for ease
				ctx.gl_state.glFrontMaterial.emission.x = 0;
			}
			if (locs.glFrontMaterial.specular != -1
					&& (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.glFrontMaterial.specular.x == Float.NEGATIVE_INFINITY))
			{
					gl.glUniform3f(locs.glFrontMaterial.specular, ctx.materialData.specular.x, ctx.materialData.specular.y,
							ctx.materialData.specular.z);
				ctx.gl_state.glFrontMaterial.specular.x = 0;
			}
			if (locs.glFrontMaterial.shininess != -1
					&& (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.glFrontMaterial.shininess != ctx.materialData.shininess))
			{
					gl.glUniform1f(locs.glFrontMaterial.shininess, ctx.materialData.shininess);
				ctx.gl_state.glFrontMaterial.shininess = ctx.materialData.shininess;
			}
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

			}

		// ambient does not come from material notice
		if (locs.glLightModelambient != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP
					|| (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.glLightModelambient.x == Float.NEGATIVE_INFINITY))
			{
				gl.glUniform4f(locs.glLightModelambient, ctx.currentAmbientColor.x, ctx.currentAmbientColor.y, ctx.currentAmbientColor.z,
						ctx.currentAmbientColor.w);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_FFP)
					ctx.gl_state.glLightModelambient.x = 0;
			}
		}

		// always bind object color, the shader can decide to use it if it's no lighting and no vertex colors
		if (locs.objectColor != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP
					|| (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.objectColor.x == Float.NEGATIVE_INFINITY))
			{
				gl.glUniform4f(locs.objectColor, ctx.objectColor.x, ctx.objectColor.y, ctx.objectColor.z, ctx.objectColor.w);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_FFP)
					ctx.gl_state.objectColor.x = 0;
			}
		}

		if (locs.transparencyAlpha != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP
					|| (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.transparencyAlpha != ctx.transparencyAlpha))
			{
				gl.glUniform1f(locs.transparencyAlpha, ctx.transparencyAlpha);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_FFP)
					ctx.gl_state.transparencyAlpha = ctx.transparencyAlpha;
			}
		}

		// count of enabled lights currentEnabledLights
		if (locs.numberOfLights != -1)
		{
			//note ctx.gl_state.numberOfLights can be -1 for not set
			if (!MINIMISE_NATIVE_CALLS_FFP
					|| (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.numberOfLights != ctx.numberOfLights))
			{
				gl.glUniform1i(locs.numberOfLights, ctx.numberOfLights);

				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_FFP)
					ctx.gl_state.numberOfLights = ctx.numberOfLights;
			}
		}

		// the lighting structures 
		int pipelineLightSlotToUse = 0;
		for (int shaderLightIndex = 0; shaderLightIndex < ctx.maxLights; shaderLightIndex++)
		{
			if (locs.glLightSource[pipelineLightSlotToUse] != null)
			{
				glLightSource glLightSource = ctx.glLightSource[shaderLightIndex];
				if (glLightSource.enabled == 1)
				{
					//Notice use of == as we want to see it's simply the exact same light source or not
					if (!MINIMISE_NATIVE_CALLS_FFP
							|| (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.glLightSource[shaderLightIndex] != glLightSource))
					{
						glLightSource.prevLightSlot = pipelineLightSlotToUse;// record for the equals to check for moved

						glLightSourceLocs glLightSourceLocs = locs.glLightSource[pipelineLightSlotToUse];
						if (glLightSourceLocs.position != -1)
							gl.glUniform4f(glLightSourceLocs.position, glLightSource.position.x, glLightSource.position.y,
									glLightSource.position.z, glLightSource.position.w);
						if (glLightSourceLocs.diffuse != -1)
							gl.glUniform4f(glLightSourceLocs.diffuse, glLightSource.diffuse.x, glLightSource.diffuse.y,
									glLightSource.diffuse.z, glLightSource.diffuse.w);
						if (glLightSourceLocs.specular != -1)
							gl.glUniform4f(glLightSourceLocs.specular, glLightSource.specular.x, glLightSource.specular.y,
									glLightSource.specular.z, glLightSource.specular.w);
						if (glLightSourceLocs.constantAttenuation != -1)
							gl.glUniform1f(glLightSourceLocs.constantAttenuation, glLightSource.constantAttenuation);
						if (glLightSourceLocs.linearAttenuation != -1)
							gl.glUniform1f(glLightSourceLocs.linearAttenuation, glLightSource.linearAttenuation);
						if (glLightSourceLocs.quadraticAttenuation != -1)
							gl.glUniform1f(glLightSourceLocs.quadraticAttenuation, glLightSource.quadraticAttenuation);
						if (glLightSourceLocs.spotCutoff != -1)
							gl.glUniform1f(glLightSourceLocs.spotCutoff, glLightSource.spotCutoff);
						if (glLightSourceLocs.spotExponent != -1)
							gl.glUniform1f(glLightSourceLocs.spotExponent, glLightSource.spotExponent);
						if (glLightSourceLocs.spotDirection != -1)
							gl.glUniform3f(glLightSourceLocs.spotDirection, glLightSource.spotDirection.x, glLightSource.spotDirection.y,
									glLightSource.spotDirection.z);

					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);
					if (MINIMISE_NATIVE_CALLS_FFP)
							ctx.gl_state.glLightSource[shaderLightIndex] = glLightSource;
					}
					pipelineLightSlotToUse++;

				}
			}
		}

		if (locs.alphaTestEnabled != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP
					|| (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.alphaTestEnabled != ctx.renderingData.alphaTestEnabled))
			{
				gl.glUniform1i(locs.alphaTestEnabled, ctx.renderingData.alphaTestEnabled ? 1 : 0);
				if (MINIMISE_NATIVE_CALLS_FFP)
					ctx.gl_state.alphaTestEnabled = ctx.renderingData.alphaTestEnabled;

				if (ctx.renderingData.alphaTestEnabled == true)
				{
					if (!MINIMISE_NATIVE_CALLS_FFP || (shaderProgramId != ctx.prevShaderProgram
							|| ctx.gl_state.alphaTestFunction != ctx.renderingData.alphaTestFunction))
					{
						gl.glUniform1i(locs.alphaTestFunction, ctx.renderingData.alphaTestFunction);
						if (MINIMISE_NATIVE_CALLS_FFP)
							ctx.gl_state.alphaTestFunction = ctx.renderingData.alphaTestFunction;
					}

					if (!MINIMISE_NATIVE_CALLS_FFP || (shaderProgramId != ctx.prevShaderProgram
							|| ctx.gl_state.alphaTestValue != ctx.renderingData.alphaTestValue))
					{
						gl.glUniform1f(locs.alphaTestValue, ctx.renderingData.alphaTestValue);
						if (MINIMISE_NATIVE_CALLS_FFP)
							ctx.gl_state.alphaTestValue = ctx.renderingData.alphaTestValue;
					}
				}
			}
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}

		if (locs.textureTransform != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP
					|| (shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.textureTransform.m00 == Double.NEGATIVE_INFINITY))
			{
				// gl.glUniformMatrix4fv(locs.textureTransform, 1, true, ctx.toFB(ctx.textureTransform));
				gl.glUniformMatrix4fv(locs.textureTransform, 1, true, ctx.matrixUtil.toArray(ctx.textureTransform), 0);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_FFP)
					ctx.gl_state.textureTransform.m00 = 0;
			}
		}

		// Fog
		if (locs.fogData.present && locs.fogData.fogEnabled != -1)
		{
			if (!MINIMISE_NATIVE_CALLS_FFP || shaderProgramId != ctx.prevShaderProgram
					|| ctx.gl_state.fogData.fogEnabled != ctx.fogData.fogEnabled)
			{
					gl.glUniform1i(locs.fogData.fogEnabled, ctx.fogData.fogEnabled);

				ctx.gl_state.fogData.fogEnabled = ctx.fogData.fogEnabled;

				if (ctx.fogData.fogEnabled == 1)
				{
					if ((shaderProgramId != ctx.prevShaderProgram || ctx.gl_state.fogData.expColor.x == Float.NEGATIVE_INFINITY))
					{
				if (locs.fogData.expColor != -1)
							gl.glUniform4f(locs.fogData.expColor, ctx.fogData.expColor.x, ctx.fogData.expColor.y, ctx.fogData.expColor.z,
									1.0f);
				if (locs.fogData.expDensity != -1)
					gl.glUniform1f(locs.fogData.expDensity, ctx.fogData.expDensity);
				if (locs.fogData.linearColor != -1)
					gl.glUniform4f(locs.fogData.linearColor, ctx.fogData.linearColor.x, ctx.fogData.linearColor.y,
							ctx.fogData.linearColor.z, 1.0f);
				if (locs.fogData.linearStart != -1)
					gl.glUniform1f(locs.fogData.linearStart, ctx.fogData.linearStart);
				if (locs.fogData.linearEnd != -1)
					gl.glUniform1f(locs.fogData.linearEnd, ctx.fogData.linearEnd);

				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_FFP)
							ctx.gl_state.fogData.expColor.x = 0;
					}
				}
			}
		}

		// record for the next loop through FFP
		ctx.prevShaderProgram = shaderProgramId;
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

	}

	private boolean NO_PROGRAM_WARNING_GIVEN = false;

	// ----------------------------------------------------------------------
	// Private helper methods for GeometryArrayRetained and IndexedGeometryArrayRetained
	//

	private static void loadLocs(Jogl2es2Context ctx, GL2ES2 gl)
	{
		ProgramData pd = ctx.programData;
		if (pd.programToLocationData == null)
		{
			LocationData locs = new LocationData();

			if (OUTPUT_PER_FRAME_STATS)
				ctx.perFrameStats.programToLocationData++;

			int shaderProgramId = ctx.shaderProgramId;
			// shader program can be disabled, but locs still called
			if (shaderProgramId != -1)
			{
				locs.glProjectionMatrix = gl.glGetUniformLocation(shaderProgramId, "glProjectionMatrix");
				locs.glProjectionMatrixInverse = gl.glGetUniformLocation(shaderProgramId, "glProjectionMatrixInverse");
				locs.glModelMatrix = gl.glGetUniformLocation(shaderProgramId, "glModelMatrix");
				locs.glViewMatrix = gl.glGetUniformLocation(shaderProgramId, "glViewMatrix");
				locs.glModelViewMatrix = gl.glGetUniformLocation(shaderProgramId, "glModelViewMatrix");
				locs.glModelViewMatrixInverse = gl.glGetUniformLocation(shaderProgramId, "glModelViewMatrixInverse");
				locs.glModelViewProjectionMatrix = gl.glGetUniformLocation(shaderProgramId, "glModelViewProjectionMatrix");
				locs.glNormalMatrix = gl.glGetUniformLocation(shaderProgramId, "glNormalMatrix");
				locs.ignoreVertexColors = gl.glGetUniformLocation(shaderProgramId, "ignoreVertexColors");
				locs.glLightModelambient = gl.glGetUniformLocation(shaderProgramId, "glLightModelambient");
				locs.objectColor = gl.glGetUniformLocation(shaderProgramId, "objectColor");
				locs.transparencyAlpha = gl.glGetUniformLocation(shaderProgramId, "transparencyAlpha");
				locs.alphaTestEnabled = gl.glGetUniformLocation(shaderProgramId, "alphaTestEnabled");
				locs.alphaTestFunction = gl.glGetUniformLocation(shaderProgramId, "alphaTestFunction");
				locs.alphaTestValue = gl.glGetUniformLocation(shaderProgramId, "alphaTestValue");
				locs.textureTransform = gl.glGetUniformLocation(shaderProgramId, "textureTransform");

				locs.fogData.fogEnabled = gl.glGetUniformLocation(shaderProgramId, "fogData.fogEnabled");
				locs.fogData.expColor = gl.glGetUniformLocation(shaderProgramId, "fogData.expColor");
				locs.fogData.expDensity = gl.glGetUniformLocation(shaderProgramId, "fogData.expDensity");
				locs.fogData.linearColor = gl.glGetUniformLocation(shaderProgramId, "fogData.linearColor");
				locs.fogData.linearStart = gl.glGetUniformLocation(shaderProgramId, "fogData.linearStart");
				locs.fogData.linearEnd = gl.glGetUniformLocation(shaderProgramId, "fogData.linearEnd");
				locs.fogData.setPresent();

				locs.glFrontMaterial.lightEnabled = gl.glGetUniformLocation(shaderProgramId, "glFrontMaterial.lightEnabled");
				locs.glFrontMaterial.ambient = gl.glGetUniformLocation(shaderProgramId, "glFrontMaterial.ambient");
				locs.glFrontMaterial.diffuse = gl.glGetUniformLocation(shaderProgramId, "glFrontMaterial.diffuse");
				locs.glFrontMaterial.emission = gl.glGetUniformLocation(shaderProgramId, "glFrontMaterial.emission");
				locs.glFrontMaterial.specular = gl.glGetUniformLocation(shaderProgramId, "glFrontMaterial.specular");
				locs.glFrontMaterial.shininess = gl.glGetUniformLocation(shaderProgramId, "glFrontMaterial.shininess");
				locs.glFrontMaterial.setPresent();

				locs.numberOfLights = gl.glGetUniformLocation(shaderProgramId, "numberOfLights");

				// lights, notice the vertex attribute is made of a string concat
				// notice we stop once the light loc is not found, as that is the max the shader will accept
				for (int i = 0; i < locs.glLightSource.length; i++)
				{
					int position = gl.glGetUniformLocation(shaderProgramId, "glLightSource[" + i + "].position");
					if (position != -1)
					{
					locs.glLightSource[i] = new glLightSourceLocs();
						locs.glLightSource[i].position = position;
					locs.glLightSource[i].diffuse = gl.glGetUniformLocation(shaderProgramId, "glLightSource[" + i + "].diffuse");
					locs.glLightSource[i].specular = gl.glGetUniformLocation(shaderProgramId, "glLightSource[" + i + "].specular");
					locs.glLightSource[i].constantAttenuation = gl.glGetUniformLocation(shaderProgramId,
							"glLightSource[" + i + "].constantAttenuation");
					locs.glLightSource[i].linearAttenuation = gl.glGetUniformLocation(shaderProgramId,
							"glLightSource[" + i + "].linearAttenuation");
					locs.glLightSource[i].quadraticAttenuation = gl.glGetUniformLocation(shaderProgramId,
							"glLightSource[" + i + "].quadraticAttenuation");
					locs.glLightSource[i].spotCutoff = gl.glGetUniformLocation(shaderProgramId, "glLightSource[" + i + "].spotCutoff");
						locs.glLightSource[i].spotExponent = gl.glGetUniformLocation(shaderProgramId,
								"glLightSource[" + i + "].spotExponent");
					locs.glLightSource[i].spotDirection = gl.glGetUniformLocation(shaderProgramId,
							"glLightSource[" + i + "].spotDirection");
				}
					else
					{
						break;
					}
				}

				///////ATTRIBUTES!!!!!!!!///////////////////
				locs.glVertex = gl.glGetAttribLocation(shaderProgramId, "glVertex");
				locs.glColor = gl.glGetAttribLocation(shaderProgramId, "glColor");
				locs.glNormal = gl.glGetAttribLocation(shaderProgramId, "glNormal");

				// tex coords, notice the vertex attribute is made of a string concat
				for (int i = 0; i < locs.glMultiTexCoord.length; i++)
				{
					locs.glMultiTexCoord[i] = gl.glGetAttribLocation(shaderProgramId, "glMultiTexCoord" + i);
				}

				// generic attributes, notice allocated on a program basis not per geom
				HashMap<String, Integer> attToIndex = pd.progToGenVertAttNameToGenVertAttIndex;
				if (attToIndex != null)
				{
					for (String attrib : attToIndex.keySet())
					{
						int index = attToIndex.get(attrib);
						int attribLoc = gl.glGetAttribLocation(shaderProgramId, attrib);
						locs.genAttIndexToLoc.put(index, new Integer(attribLoc));
					}
				}

				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}

			pd.programToLocationData = locs;
		}
	}

	/**
	 * The buffers will be loaded and pointers set in GeometryData for this geometry native id
	 * Note however morphable data will also be reloaded (only coords for now)
	 * @param ctx
	 * @param gl
	 * @param geo
	 * @param ignoreVertexColors
	 * @param vertexCount
	 * @param vformat
	 * @param vdefined
	 * @param fverts
	 * @param startVertex
	 * @param fclrs
	 * @param startClrs
	 * @return
	 */
	private static GeometryData loadAllBuffers(Jogl2es2Context ctx, GL2ES2 gl, GeometryArrayRetained geo, boolean ignoreVertexColors,
			int vertexCount, int vformat, int vdefined, FloatBuffer fverts, float[] vfarray, int startVertex, FloatBuffer fclrs,
			float[] cfarray, int startClrs)
	{
		if (VERBOSE)
			System.err.println("private static GeometryData loadAllBuffers");

		GeometryData gd = ctx.allGeometryData.get(geo.nativeId);
		if (gd == null)
		{
			gd = new GeometryData();
			geo.nativeId = gd.nativeId;
			ctx.allGeometryData.put(geo.nativeId, gd);
		}

		if (gd.geoToCoordBuf == -1)
		{

			// do we need to covert a float[]
			if (vfarray != null)
			{
				fverts = getVertexArrayBuffer(vfarray);
			}
			// can it change ever? (GeometryArray.ALLOW_REF_DATA_WRITE is just my indicator of this feature)
			boolean morphable = geo.source.getCapability(GeometryArray.ALLOW_REF_DATA_WRITE)
					|| geo.source.getCapability(GeometryArray.ALLOW_COORDINATE_WRITE);

			fverts.position(startVertex);

			if (morphable)
			{
				int[] tmp = new int[2];
				gl.glGenBuffers(2, tmp, 0);
				gd.geoToCoordBuf = tmp[0];
				gd.geoToCoordBuf1 = tmp[0];
				gd.geoToCoordBuf2 = tmp[1];
				gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf1);
				int usage = morphable ? GL2ES2.GL_DYNAMIC_DRAW : GL2ES2.GL_STATIC_DRAW;
				gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (fverts.remaining() * Float.SIZE / 8), fverts, usage);

				gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf2);
				gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (fverts.remaining() * Float.SIZE / 8), fverts, usage);
			}
			else
			{
				int[] tmp = new int[1];
				gl.glGenBuffers(1, tmp, 0);
				gd.geoToCoordBuf = tmp[0];

				gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
				int usage = morphable ? GL2ES2.GL_DYNAMIC_DRAW : GL2ES2.GL_STATIC_DRAW;
				gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (fverts.remaining() * Float.SIZE / 8), fverts, usage);
			}
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);

			gd.geoToCoordBufSize = fverts.remaining();

			if (ctx.allGeometryData.size() % 500 == 0)
			{
				System.out.println("Coord buffer count " + ctx.allGeometryData.size());
			}

			if (OUTPUT_PER_FRAME_STATS)
				ctx.perFrameStats.glBufferData++;
		}

		if (!ignoreVertexColors)
		{
			if (gd.geoToColorBuf == -1)
			{
				if (cfarray != null)
				{
					fclrs = getColorArrayBuffer(cfarray);
				}
				if (fclrs != null)
				{
					if (fclrs != fverts)
					{
						fclrs.position(startClrs);
						int[] tmp = new int[1];
						gl.glGenBuffers(1, tmp, 0);
						gd.geoToColorBuf = tmp[0];

						gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToColorBuf);
						gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, fclrs.remaining() * Float.SIZE / 8, fclrs, GL2ES2.GL_STATIC_DRAW);
					}
					else
					{
						gd.geoToColorBuf = gd.geoToCoordBuf;
					}
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);

					if (OUTPUT_PER_FRAME_STATS)
						ctx.perFrameStats.glBufferData++;
				}
			}
		}

		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

		return gd;
	}

	private static GeometryData loadAllBuffers(Jogl2es2Context ctx, GL2ES2 gl, GeometryArrayRetained geo, boolean ignoreVertexColors,
			int vertexCount, int vformat, int vdefined, FloatBuffer fverts, float[] vfcoords, DoubleBuffer dverts, double[] vdcoords,
			FloatBuffer fclrs, float[] cfarray, ByteBuffer bclrs, byte[] cbdata, FloatBuffer norms, float[] narray, int vertexAttrCount,
			int[] vertexAttrSizes, FloatBuffer[] vertexAttrBufs, float[][] vertexAttrData, int texCoordMapLength, int[] texCoordSetMap,
			int texStride, Object[] texCoords)
	{
		if (VERBOSE)
			System.err.println("private static GeometryData loadAllBuffers");

		GeometryData gd = ctx.allGeometryData.get(geo.nativeId);
		if (gd == null)
		{
			gd = new GeometryData();
			geo.nativeId = gd.nativeId;
			ctx.allGeometryData.put(geo.nativeId, gd);
		}

		boolean floatCoordDefined = ((vdefined & GeometryArrayRetained.COORD_FLOAT) != 0);
		boolean doubleCoordDefined = ((vdefined & GeometryArrayRetained.COORD_DOUBLE) != 0);
		boolean floatColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_FLOAT) != 0);
		boolean byteColorsDefined = ((vdefined & GeometryArrayRetained.COLOR_BYTE) != 0);
		boolean normalsDefined = ((vdefined & GeometryArrayRetained.NORMAL_FLOAT) != 0);
		boolean vattrDefined = ((vdefined & GeometryArrayRetained.VATTR_FLOAT) != 0);
		boolean textureDefined = ((vdefined & GeometryArrayRetained.TEXCOORD_FLOAT) != 0);

		if (floatCoordDefined)
		{
			if (gd.geoToCoordBuf == -1)
			{
				// do we need to covert a float[]
				if (vfcoords != null)
				{
					fverts = getVertexArrayBuffer(vfcoords);
				}

				// can it change ever? (GeometryArray.ALLOW_REF_DATA_WRITE is just my indicator of this feature)
				boolean morphable = geo.source.getCapability(GeometryArray.ALLOW_REF_DATA_WRITE)
						|| geo.source.getCapability(GeometryArray.ALLOW_COORDINATE_WRITE);

				fverts.position(0);

				if (morphable)
				{
					int[] tmp = new int[2];
					gl.glGenBuffers(2, tmp, 0);
					gd.geoToCoordBuf = tmp[0];
					gd.geoToCoordBuf1 = tmp[0];
					gd.geoToCoordBuf2 = tmp[1];
					gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf1);
					int usage = morphable ? GL2ES2.GL_DYNAMIC_DRAW : GL2ES2.GL_STATIC_DRAW;
					gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (fverts.remaining() * Float.SIZE / 8), fverts, usage);

					gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf2);
					gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (fverts.remaining() * Float.SIZE / 8), fverts, usage);
				}
				else
				{
					int[] tmp = new int[1];
					gl.glGenBuffers(1, tmp, 0);
					gd.geoToCoordBuf = tmp[0];

					gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToCoordBuf);
					int usage = morphable ? GL2ES2.GL_DYNAMIC_DRAW : GL2ES2.GL_STATIC_DRAW;
					gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (fverts.remaining() * Float.SIZE / 8), fverts, usage);
				}
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

				gd.geoToCoordBufSize = fverts.remaining();

				if (ctx.allGeometryData.size() % 500 == 0)
				{
					System.out.println("Coord buffer count " + ctx.allGeometryData.size());
				}

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glBufferData++;

			}
		}

		if (floatColorsDefined && !ignoreVertexColors)
		{
			if (gd.geoToColorBuf == -1)
			{
				if (cfarray != null)
				{
					fclrs = getColorArrayBuffer(cfarray);
				}

				fclrs.position(0);
				int[] tmp = new int[1];
				gl.glGenBuffers(1, tmp, 0);
				gd.geoToColorBuf = tmp[0];

				gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToColorBuf);
				gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, fclrs.remaining() * Float.SIZE / 8, fclrs, GL2ES2.GL_STATIC_DRAW);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glBufferData++;

			}
		}

		if (normalsDefined)
		{
			if (gd.geoToNormalBuf == -1)
			{
				if (narray != null)
				{
					norms = getNormalArrayBuffer(narray);
				}

				norms.position(0);

				int[] tmp = new int[1];
				gl.glGenBuffers(1, tmp, 0);
				gd.geoToNormalBuf = tmp[0];

				gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, gd.geoToNormalBuf);
				gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, norms.remaining() * Float.SIZE / 8, norms, GL2ES2.GL_STATIC_DRAW);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

				if (OUTPUT_PER_FRAME_STATS)
					ctx.perFrameStats.glBufferData++;

			}
		}

		if (vattrDefined)
		{
			if (vertexAttrData != null)
			{
				vertexAttrBufs = getVertexAttrSetBuffer(vertexAttrData);
			}

			for (int index = 0; index < vertexAttrCount; index++)
			{
				FloatBuffer vertexAttrs = vertexAttrBufs[index];
				vertexAttrs.position(0);

				SparseArray<Integer> bufIds = gd.geoToVertAttribBuf;
				if (bufIds == null)
				{
					bufIds = new SparseArray<Integer>();
					gd.geoToVertAttribBuf = bufIds;
				}

				Integer bufId = bufIds.get(index);
				if (bufId == null)
				{
					int[] tmp2 = new int[1];
					gl.glGenBuffers(1, tmp2, 0);
					bufId = new Integer(tmp2[0]);
					bufIds.put(index, bufId);

					gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, bufId.intValue());
					gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, vertexAttrs.remaining() * Float.SIZE / 8, vertexAttrs, GL2ES2.GL_STATIC_DRAW);
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);

					if (OUTPUT_PER_FRAME_STATS)
						ctx.perFrameStats.glBufferData++;

				}
			}
		}

		if (textureDefined)
		{
			// convert from float[][] to FloatBuffer[]
			//WOW careful java has a hard time knowing what an Object[] contains can't check float[][]
			if (!(texCoords[0] instanceof FloatBuffer))
			{
				texCoords = getTexCoordSetBuffer(texCoords);
			}

			boolean[] texSetsLoaded = new boolean[texCoords.length];
			for (int texUnit = 0; texUnit < texCoordMapLength; texUnit++)
			{
				int texSet = texCoordSetMap[texUnit];
				if (texSet != -1 && !texSetsLoaded[texSet])
				{
					texSetsLoaded[texSet] = true;
					// stupid interface...
					FloatBuffer buf = (FloatBuffer) texCoords[texSet];
					buf.position(0);

					SparseArray<Integer> bufIds = gd.geoToTexCoordsBuf;
					if (bufIds == null)
					{
						bufIds = new SparseArray<Integer>();
						gd.geoToTexCoordsBuf = bufIds;
					}

					Integer bufId = bufIds.get(texUnit);
					if (bufId == null)
					{
						int[] tmp = new int[1];
						gl.glGenBuffers(1, tmp, 0);
						bufId = new Integer(tmp[0]);

						gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, bufId.intValue());
						gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, buf.remaining() * Float.SIZE / 8, buf, GL2ES2.GL_STATIC_DRAW);
						if (DO_OUTPUT_ERRORS)
							outputErrors(ctx);
						bufIds.put(texUnit, bufId);

						if (OUTPUT_PER_FRAME_STATS)
							ctx.perFrameStats.glBufferData++;
					}
				}
			}
		}

		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

		return gd;
	}

	// --Noop
	@Override
	void setVertexFormat(Context ctx, GeometryArrayRetained geo, int vformat, boolean useAlpha, boolean ignoreVertexColors)
	{
		// if (VERBOSE)
		// System.err.println("JoglPipeline.setVertexFormat()");
	}

	// ---------------------------------------------------------------------
	// Native method for readRaster
	@Override
	void readRaster(Context ctx, int type, int xSrcOffset, int ySrcOffset, int width, int height, int hCanvas, int imageDataType,
			int imageFormat, Object imageBuffer, int depthFormat, Object depthBuffer)
	{
		Jogl2es2Context joglesctx = (Jogl2es2Context) ctx;
		GL2ES2 gl = joglesctx.gl2es2();

		//gl.glPixelStorei(GL2.GL_PACK_ROW_LENGTH, width);
		gl.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		int yAdjusted = hCanvas - height - ySrcOffset;

		if ((type & Raster.RASTER_COLOR) != 0)
		{
			int format = 0;
			if (imageDataType == ImageComponentRetained.IMAGE_DATA_TYPE_BYTE_ARRAY)
			{

				switch (imageFormat)
				{
				case ImageComponentRetained.TYPE_BYTE_BGR:
					format = GL2ES2.GL_BGR;
					break;
				case ImageComponentRetained.TYPE_BYTE_RGB:
					format = GL2ES2.GL_RGB;
					break;
				case ImageComponentRetained.TYPE_BYTE_ABGR:
					format = GL2ES2.GL_RGBA;
					break;
				case ImageComponentRetained.TYPE_BYTE_RGBA:
					// all RGB types are stored as RGBA
					format = GL2ES2.GL_RGBA;
					break;
				case ImageComponentRetained.TYPE_BYTE_LA:
					// all LA types are stored as LA8
					format = GL2ES2.GL_LUMINANCE_ALPHA;
					break;
				case ImageComponentRetained.TYPE_BYTE_GRAY:
				case ImageComponentRetained.TYPE_USHORT_GRAY:
				case ImageComponentRetained.TYPE_INT_BGR:
				case ImageComponentRetained.TYPE_INT_RGB:
				case ImageComponentRetained.TYPE_INT_ARGB:
				default:
					assert false;
					return;
				}

				gl.glReadPixels(xSrcOffset, yAdjusted, width, height, format, GL2ES2.GL_UNSIGNED_BYTE,
						ByteBuffer.wrap((byte[]) imageBuffer));
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}
			else if (imageDataType == ImageComponentRetained.IMAGE_DATA_TYPE_INT_ARRAY)
			{
				switch (imageFormat)
				{
				case ImageComponentRetained.TYPE_INT_BGR:  
					//PJ does this work correctly?
					format = GL2ES2.GL_RGB;
					break;
				case ImageComponentRetained.TYPE_INT_RGB:  
					format = GL2ES2.GL_RGB;
					break;
				case ImageComponentRetained.TYPE_INT_ARGB:
					format = GL2ES2.GL_RGBA;
					break;
				// This method only supports 3 and 4 components formats and INT types.			
				case ImageComponentRetained.TYPE_BYTE_LA:
				case ImageComponentRetained.TYPE_BYTE_GRAY:
				case ImageComponentRetained.TYPE_USHORT_GRAY:
				case ImageComponentRetained.TYPE_BYTE_BGR:
				case ImageComponentRetained.TYPE_BYTE_RGB:
				case ImageComponentRetained.TYPE_BYTE_RGBA:
				case ImageComponentRetained.TYPE_BYTE_ABGR:
				default:
					assert false;
					return;
				}

				gl.glReadPixels(xSrcOffset, yAdjusted, width, height, format, GL2ES2.GL_UNSIGNED_BYTE, IntBuffer.wrap((int[]) imageBuffer));
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

			}
			else
			{
				assert false;
			}
		}

		if ((type & Raster.RASTER_DEPTH) != 0)
		{
			throw new UnsupportedOperationException(
					"To get depth you should use a shader that return depth info for gl2es2 then read from color");
			/*if (depthFormat == DepthComponentRetained.DEPTH_COMPONENT_TYPE_INT)
			{
				// yOffset is adjusted for OpenGL - Y upward
				gl.glReadPixels(xSrcOffset, yAdjusted, width, height, GL2.GL_DEPTH_COMPONENT, GL.GL_UNSIGNED_INT,
						IntBuffer.wrap((int[]) depthBuffer));
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}
			else
			{
				// DEPTH_COMPONENT_TYPE_FLOAT
				// yOffset is adjusted for OpenGL - Y upward
				gl.glReadPixels(xSrcOffset, yAdjusted, width, height, GL2.GL_DEPTH_COMPONENT, GL.GL_FLOAT,
						FloatBuffer.wrap((float[]) depthBuffer));
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}*/
		}

	}

	// ---------------------------------------------------------------------

	//
	// GLSLShaderProgramRetained methods
	//

	// ShaderAttributeValue methods

	@Override
	ShaderError setGLSLUniform1i(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform1i(shaderProgramId = " + unbox(shaderProgramId) + ",uniformLocation="
					+ unbox(uniformLocation) + ",value=" + value + ")");

		Jogl2es2Context joglesctx = (Jogl2es2Context) ctx;
		GL2ES2 gl = joglesctx.gl2es2();
		int loc = unbox(uniformLocation);
		if (!MINIMISE_NATIVE_SHADER || joglesctx.gl_state.setGLSLUniform1i[loc] != value)
		{
			gl.glUniform1i(loc, value);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
			if (MINIMISE_NATIVE_SHADER)
				joglesctx.gl_state.setGLSLUniform1i[loc] = value;
		}
		return null;
	}

	@Override
	ShaderError setGLSLUniform1f(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, float value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform1f(shaderProgramId = " + unbox(shaderProgramId) + ",uniformLocation="
					+ unbox(uniformLocation) + ",value=" + value + ")");

		Jogl2es2Context joglesctx = (Jogl2es2Context) ctx;
		GL2ES2 gl = joglesctx.gl2es2();
		int loc = unbox(uniformLocation);
		if (!MINIMISE_NATIVE_SHADER || joglesctx.gl_state.setGLSLUniform1f[loc] != value)
		{
			gl.glUniform1f(loc, value);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
			if (MINIMISE_NATIVE_SHADER)
				joglesctx.gl_state.setGLSLUniform1f[loc] = value;
		}
		return null;
	}

	@Override
	ShaderError setGLSLUniform2i(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform2i(shaderProgramId = " + unbox(shaderProgramId) + ",uniformLocation="
					+ unbox(uniformLocation) + ",value[0]=" + value[0] + ")");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform2i(unbox(uniformLocation), value[0], value[1]);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniform2f(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, float[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform2f(shaderProgramId = " + unbox(shaderProgramId) + ",uniformLocation="
					+ unbox(uniformLocation) + ",value[0]=" + value[0] + ")");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform2f(unbox(uniformLocation), value[0], value[1]);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniform3i(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform3i(shaderProgramId = " + unbox(shaderProgramId) + ",uniformLocation="
					+ unbox(uniformLocation) + ",value[0]=" + value[0] + ")");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform3i(unbox(uniformLocation), value[0], value[1], value[2]);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniform3f(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, float[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform3f(shaderProgramId = " + unbox(shaderProgramId) + ",uniformLocation="
					+ unbox(uniformLocation) + ",value[0]=" + value[0] + ")");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform3f(unbox(uniformLocation), value[0], value[1], value[2]);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniform4i(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform4i(shaderProgramId = " + unbox(shaderProgramId) + ",uniformLocation="
					+ unbox(uniformLocation) + ",value[0]=" + value[0] + ")");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform4i(unbox(uniformLocation), value[0], value[1], value[2], value[3]);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniform4f(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, float[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform4f(shaderProgramId = " + unbox(shaderProgramId) + ",uniformLocation="
					+ unbox(uniformLocation) + ",value[0]=" + value[0] + ")");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform4f(unbox(uniformLocation), value[0], value[1], value[2], value[3]);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniformMatrix3f(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, float[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniformMatrix3f(shaderProgramId = " + unbox(shaderProgramId) + ",uniformLocation="
					+ unbox(uniformLocation) + ",value[0]=" + value[0] + ")");

		// Load attribute
		// transpose is true : each matrix is supplied in row major order
		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniformMatrix3fv(unbox(uniformLocation), 1, false, ((Jogl2es2Context) ctx).matrixUtil.toFB3(value));
		// gl.glUniformMatrix3fv(unbox(uniformLocation), 1, true, value, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniformMatrix4f(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, float[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniformMatrix4f(shaderProgramId = " + unbox(shaderProgramId) + ",uniformLocation="
					+ unbox(uniformLocation) + ",value[0]=" + value[0] + ")");

		// Load attribute
		// transpose is true : each matrix is supplied in row major order
		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniformMatrix4fv(unbox(uniformLocation), 1, false, ((Jogl2es2Context) ctx).matrixUtil.toFB4(value));
		// gl.glUniformMatrix4fv(unbox(uniformLocation), 1, true, value, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	// ShaderAttributeArray methods

	@Override
	ShaderError setGLSLUniform1iArray(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int numElements,
			int[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform1iArray()");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform1iv(unbox(uniformLocation), numElements, value, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniform1fArray(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int numElements,
			float[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform1fArray()");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform1fv(unbox(uniformLocation), numElements, value, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniform2iArray(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int numElements,
			int[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform2iArray()");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform2iv(unbox(uniformLocation), numElements, value, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniform2fArray(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int numElements,
			float[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform2fArray()");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform2fv(unbox(uniformLocation), numElements, value, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniform3iArray(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int numElements,
			int[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform3iArray()");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform3iv(unbox(uniformLocation), numElements, value, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniform3fArray(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int numElements,
			float[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform3fArray()");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform3fv(unbox(uniformLocation), numElements, value, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniform4iArray(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int numElements,
			int[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform4iArray()");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform4iv(unbox(uniformLocation), numElements, value, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniform4fArray(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int numElements,
			float[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniform4fArray()");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniform4fv(unbox(uniformLocation), numElements, value, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniformMatrix3fArray(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int numElements,
			float[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniformMatrix3fArray()");

		// Load attribute
		// transpose is true : each matrix is supplied in row major order
		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniformMatrix3fv(unbox(uniformLocation), numElements, true, value, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError setGLSLUniformMatrix4fArray(Context ctx, ShaderProgramId shaderProgramId, ShaderAttrLoc uniformLocation, int numElements,
			float[] value)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setGLSLUniformMatrix4fArray()");

		// Load attribute
		// transpose is true : each matrix is supplied in row major order
		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glUniformMatrix4fv(unbox(uniformLocation), numElements, true, value, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	// interfaces for shader compilation, etc.
	@Override
	ShaderError createGLSLShader(Context ctx, int shaderType, ShaderId[] shaderId)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.createGLSLShader()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.createGLSLShader++;

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		int shaderHandle = 0;
		if (shaderType == Shader.SHADER_TYPE_VERTEX)
		{
			shaderHandle = gl.glCreateShader(GL2ES2.GL_VERTEX_SHADER);
		}
		else if (shaderType == Shader.SHADER_TYPE_FRAGMENT)
		{
			shaderHandle = gl.glCreateShader(GL2ES2.GL_FRAGMENT_SHADER);
		}
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		if (shaderHandle == 0)
		{
			return new ShaderError(ShaderError.COMPILE_ERROR, "Unable to create native shader object");
		}

		shaderId[0] = new JoglShaderObject(shaderHandle);

		return null;
	}

	@Override
	ShaderError destroyGLSLShader(Context ctx, ShaderId shaderId)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.destroyGLSLShader()");

		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.destroyGLSLShader++;

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glDeleteShader(unbox(shaderId));
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		return null;
	}

	@Override
	ShaderError compileGLSLShader(Context ctx, ShaderId shaderId, String program)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.compileGLSLShader()");

		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.compileGLSLShader++;

		int id = unbox(shaderId);
		if (id == 0)
		{
			throw new AssertionError("shaderId == 0");
		}

		if (program == null)
		{
			throw new AssertionError("shader program string is null");
		}

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		gl.glShaderSource(id, 1, new String[] { program }, null, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		gl.glCompileShader(id);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		int[] status = new int[1];
		gl.glGetShaderiv(id, GL2ES2.GL_COMPILE_STATUS, status, 0);
		if (status[0] == 0)
		{
			String detailMsg = getShaderInfoLog(gl, id);
			ShaderError res = new ShaderError(ShaderError.COMPILE_ERROR, "GLSL shader compile error");
			res.setDetailMessage(detailMsg);
			return res;
		}
		return null;
	}

	@Override
	ShaderError createGLSLShaderProgram(Context ctx, ShaderProgramId[] shaderProgramId)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.createGLSLShaderProgram()");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.createGLSLShaderProgram++;

		int shaderProgramHandle = gl.glCreateProgram();
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		if (shaderProgramHandle == 0)
		{
			return new ShaderError(ShaderError.LINK_ERROR, "Unable to create native shader program object");
		}
		shaderProgramId[0] = new JoglShaderObject(shaderProgramHandle);

		return null;
	}

	@Override
	ShaderError destroyGLSLShaderProgram(Context ctx, ShaderProgramId shaderProgramId)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.destroyGLSLShaderProgram()");

		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.destroyGLSLShaderProgram++;

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glDeleteShader(unbox(shaderProgramId));
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

		// just dump data
		((Jogl2es2Context) ctx).allProgramData.remove(unbox(shaderProgramId));

		return null;
	}

	@Override
	ShaderError linkGLSLShaderProgram(Context ctx, ShaderProgramId shaderProgramId, ShaderId[] shaderIds)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.linkGLSLShaderProgram()");

		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.linkGLSLShaderProgram++;

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		int id = unbox(shaderProgramId);
		for (int i = 0; i < shaderIds.length; i++)
		{
			gl.glAttachShader(id, unbox(shaderIds[i]));
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}
		gl.glLinkProgram(id);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		int[] status = new int[1];
		gl.glGetProgramiv(id, GL2ES2.GL_LINK_STATUS, status, 0);
		if (status[0] == 0)
		{
			String detailMsg = getProgramInfoLog(gl, id);
			ShaderError res = new ShaderError(ShaderError.LINK_ERROR, "GLSL shader program link error");
			res.setDetailMessage(detailMsg);
			return res;
		}
		return null;
	}

	@Override
	ShaderError bindGLSLVertexAttrName(Context ctx, ShaderProgramId shaderProgramId, String attrName, int attrIndex)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.bindGLSLVertexAttrName()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.bindGLSLVertexAttrName++;

		// GL2ES2 gl = context(ctx).getGL().getGL2ES2();
		// gl.glBindAttribLocation(unbox(shaderProgramId), attrIndex + VirtualUniverse.mc.glslVertexAttrOffset, attrName);

		// record this for later, we'll get real locations in the locationData setup
		int progId = unbox(shaderProgramId);
		Jogl2es2Context joglesContext = (Jogl2es2Context) ctx;
		ProgramData pd = joglesContext.allProgramData.get(progId);
		if (pd == null)
		{
			pd = new ProgramData();
			joglesContext.allProgramData.put(progId, pd);
		}

		HashMap<String, Integer> attToIndex = pd.progToGenVertAttNameToGenVertAttIndex;
		if (attToIndex == null)
		{
			attToIndex = new HashMap<String, Integer>();
			pd.progToGenVertAttNameToGenVertAttIndex = attToIndex;
		}

		attToIndex.put(attrName, attrIndex);

		return null;
	}

	@Override
	void lookupGLSLShaderAttrNames(Context ctx, ShaderProgramId shaderProgramId, int numAttrNames, String[] attrNames,
			ShaderAttrLoc[] locArr, int[] typeArr, int[] sizeArr, boolean[] isArrayArr)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.lookupGLSLShaderAttrNames()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.lookupGLSLShaderAttrNames++;

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		// set the loc, type, and size arrays to out-of-bound values
		for (int i = 0; i < attrNames.length; i++)
		{
			locArr[i] = null;
			typeArr[i] = -1;
			sizeArr[i] = -1;
		}

		// Loop through the list of active uniform variables, one at a
		// time, searching for a match in the attrNames array.
		//
		// NOTE: Since attrNames isn't sorted, and we don't have a
		// hashtable of names to index locations, we will do a
		// brute-force, linear search of the array. This leads to an
		// O(n^2) algorithm (actually O(n*m) where n is attrNames.length
		// and m is the number of uniform variables), but since we expect
		// N to be small, we will not optimize this at this time.
		int id = unbox(shaderProgramId);
		int[] tmp = new int[1];
		int[] tmp2 = new int[1];
		int[] tmp3 = new int[1];

		gl.glGetProgramiv(id, GL2ES2.GL_ACTIVE_UNIFORMS, tmp, 0);
		int numActiveUniforms = tmp[0];
		gl.glGetProgramiv(id, GL2ES2.GL_ACTIVE_UNIFORM_MAX_LENGTH, tmp, 0);
		int maxStrLen = tmp[0];
		byte[] nameBuf = new byte[maxStrLen];

		for (int i = 0; i < numActiveUniforms; i++)
		{
			gl.glGetActiveUniform(id, i, maxStrLen, tmp3, 0, tmp, 0, tmp2, 0, nameBuf, 0);
			int size = tmp[0];
			int type = tmp2[0];
			String name = null;
			try
			{
				name = new String(nameBuf, 0, tmp3[0], "US-ASCII");
			}
			catch (UnsupportedEncodingException e)
			{
				throw new RuntimeException(e);
			}

			// Issue 247 - we need to workaround an ATI bug where they erroneously
			// report individual elements of arrays rather than the array itself
			if (name.length() >= 3 && name.endsWith("]"))
			{
				if (name.endsWith("[0]"))
				{
					name = name.substring(0, name.length() - 3);
				}
				else
				{
					// Ignore this name
					continue;
				}
			}

			// Now try to find the name
			for (int j = 0; j < numAttrNames; j++)
			{
				if (name.equals(attrNames[j]))
				{
					sizeArr[j] = size;
					isArrayArr[j] = (size > 1);
					typeArr[j] = glslToJ3dType(type);
					break;
				}
			}
		}

		// Now lookup the location of each name in the attrNames array
		for (int i = 0; i < numAttrNames; i++)
		{
			// Get uniform attribute location
			int loc = gl.glGetUniformLocation(id, attrNames[i]);
			locArr[i] = new JoglShaderObject(loc);
		}

		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
	}

	// good ideas about uniform debugging
	// http://stackoverflow.com/questions/26164602/unexplainable-gl-invalid-operation-from-gluniform1i-opengl-thinks-an-int-is-a-f
	private boolean USE_NULL_SHADER_WARNING_GIVEN = false;

	@Override
	ShaderError useGLSLShaderProgram(Context ctx, ShaderProgramId inShaderProgramId)
	{
		int shaderProgramId = unbox(inShaderProgramId);
		if (VERBOSE)
			System.err.println("JoglPipeline.useGLSLShaderProgram(shaderProgramId=" + shaderProgramId + ")");

		Jogl2es2Context joglesContext = (Jogl2es2Context) ctx;
		if (OUTPUT_PER_FRAME_STATS)
		{
			if (joglesContext.gl_state.currentProgramId == shaderProgramId)
			{
				joglesContext.perFrameStats.redundantUseProgram++;
			}
			else
			{
				joglesContext.perFrameStats.useGLSLShaderProgram++;
				joglesContext.perFrameStats.usedPrograms.add(inShaderProgramId);
			}
		}

		if (!MINIMISE_NATIVE_SHADER || joglesContext.gl_state.currentProgramId != shaderProgramId)
		{
			if (shaderProgramId == -1)
			{
				if (!USE_NULL_SHADER_WARNING_GIVEN)
					System.err.println("Null shader passed for use");
				USE_NULL_SHADER_WARNING_GIVEN = true;
			}

			GL2ES2 gl = joglesContext.gl2es2();

			gl.glUseProgram(shaderProgramId);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);

			joglesContext.setShaderProgram((JoglShaderObject) inShaderProgramId);
			loadLocs(joglesContext, gl);

			if (MINIMISE_NATIVE_SHADER)
				joglesContext.gl_state.currentProgramId = shaderProgramId;

		}
		return null;
	}

	// ----------------------------------------------------------------------
	// Helper methods for above shader routines
	//
	private static int unbox(ShaderAttrLoc loc)
	{
		if (loc == null)
			return -1;//0 is a valid location
		return ((JoglShaderObject) loc).getValue();
	}

	private static int unbox(ShaderProgramId id)
	{
		if (id == null)
			return 0;
		return ((JoglShaderObject) id).getValue();
	}

	private static int unbox(ShaderId id)
	{
		if (id == null)
			return 0;
		return ((JoglShaderObject) id).getValue();
	}

	private static String getShaderInfoLog(GL2ES2 gl, int id)
	{
		int[] infoLogLength = new int[1];
		gl.glGetShaderiv(id, GL2ES2.GL_INFO_LOG_LENGTH, infoLogLength, 0);
		if (infoLogLength[0] > 0)
		{
			byte[] storage = new byte[infoLogLength[0]];
			int[] len = new int[1];
			gl.glGetShaderInfoLog(id, infoLogLength[0], len, 0, storage, 0);
			try
			{
				return new String(storage, 0, len[0], "US-ASCII");
			}
			catch (UnsupportedEncodingException e)
			{
				throw new RuntimeException(e);
			}
		}
		return null;
	}

	private static String getProgramInfoLog(GL2ES2 gl, int id)
	{
		int[] infoLogLength = new int[1];
		gl.glGetProgramiv(id, GL2ES2.GL_INFO_LOG_LENGTH, infoLogLength, 0);
		if (infoLogLength[0] > 0)
		{
			byte[] storage = new byte[infoLogLength[0]];
			int[] len = new int[1];
			gl.glGetProgramInfoLog(id, infoLogLength[0], len, 0, storage, 0);
			try
			{
				return new String(storage, 0, len[0], "US-ASCII");
			}
			catch (UnsupportedEncodingException e)
			{
				throw new RuntimeException(e);
			}
		}
		return null;
	}

	private static int glslToJ3dType(int type)
	{
		switch (type)
		{
		case GL2ES2.GL_BOOL:
		case GL2ES2.GL_INT:
		case GL2ES2.GL_SAMPLER_2D:
		case GL2ES2.GL_SAMPLER_3D:
		case GL2ES2.GL_SAMPLER_CUBE:
			return ShaderAttributeObjectRetained.TYPE_INTEGER;

		case GL2ES2.GL_FLOAT:
			return ShaderAttributeObjectRetained.TYPE_FLOAT;

		case GL2ES2.GL_INT_VEC2:
		case GL2ES2.GL_BOOL_VEC2:
			return ShaderAttributeObjectRetained.TYPE_TUPLE2I;

		case GL2ES2.GL_FLOAT_VEC2:
			return ShaderAttributeObjectRetained.TYPE_TUPLE2F;

		case GL2ES2.GL_INT_VEC3:
		case GL2ES2.GL_BOOL_VEC3:
			return ShaderAttributeObjectRetained.TYPE_TUPLE3I;

		case GL2ES2.GL_FLOAT_VEC3:
			return ShaderAttributeObjectRetained.TYPE_TUPLE3F;

		case GL2ES2.GL_INT_VEC4:
		case GL2ES2.GL_BOOL_VEC4:
			return ShaderAttributeObjectRetained.TYPE_TUPLE4I;

		case GL2ES2.GL_FLOAT_VEC4:
			return ShaderAttributeObjectRetained.TYPE_TUPLE4F;

		// case GL2ES2.GL_FLOAT_MAT2:

		case GL2ES2.GL_FLOAT_MAT3:
			return ShaderAttributeObjectRetained.TYPE_MATRIX3F;

		case GL2ES2.GL_FLOAT_MAT4:
			return ShaderAttributeObjectRetained.TYPE_MATRIX4F;

		// Java 3D does not support the following sampler types:
		//
		// case GL2ES2.GL_SAMPLER_1D_ARB:
		// case GL2ES2.GL_SAMPLER_1D_SHADOW_ARB:
		// case GL2ES2.GL_SAMPLER_2D_SHADOW_ARB:
		// case GL2ES2.GL_SAMPLER_2D_RECT_ARB:
		// case GL2ES2.GL_SAMPLER_2D_RECT_SHADOW_ARB:
		}

		return -1;
	}

	// ---------------------------------------------------------------------

	private static final Vector4f black = new Vector4f();

	//
	// DirectionalLightRetained methods
	//
	@Override
	void updateDirectionalLight(Context ctx, int lightSlot, float red, float green, float blue, float dirx, float diry, float dirz)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateDirectionalLight() " + lightSlot + " " + red + " " + green + " " + blue + " " + dirx
					+ " " + diry + " " + dirz);

		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updateDirectionalLight++;

		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);

		// note the current state of MV MUST be used by the lights when setting position!
		// https://www.opengl.org/discussion_boards/showthread.php/168706-Light-Position-in-eye-s-cordinate

		// note can't use the modelview as it's  calced late

		//TODO:? possibly directional should only take the view mat, but surely I'd get a blank model??
		Vector4f lightPos = joglesctx.matrixUtil.transform(joglesctx.currentModelMat, joglesctx.currentViewMat, -dirx, -diry, -dirz, 0f);

		if (joglesctx.glLightSource[lightSlot] == null)
		{
			joglesctx.glLightSource[lightSlot] = new glLightSource();
			joglesctx.gl_state.glLightSource[lightSlot] = null;
		}

		joglesctx.glLightSource[lightSlot].diffuse.x = red;
		joglesctx.glLightSource[lightSlot].diffuse.y = green;
		joglesctx.glLightSource[lightSlot].diffuse.z = blue;
		joglesctx.glLightSource[lightSlot].diffuse.w = 1.0f;
		joglesctx.glLightSource[lightSlot].specular.x = red;
		joglesctx.glLightSource[lightSlot].specular.y = green;
		joglesctx.glLightSource[lightSlot].specular.z = blue;
		joglesctx.glLightSource[lightSlot].specular.w = 1.0f;
		joglesctx.glLightSource[lightSlot].position.x = lightPos.x;
		joglesctx.glLightSource[lightSlot].position.y = lightPos.y;
		joglesctx.glLightSource[lightSlot].position.z = lightPos.z;
		joglesctx.glLightSource[lightSlot].position.w = 0.0f;// 0 means directional light
		//joglesctx.glLightSource[lightSlot].ambient = black;// odd
		// joglesctx.glLightSource[lightSlot].GL_POSITION = 1.0f; // what is this?
		joglesctx.glLightSource[lightSlot].constantAttenuation = 1.0f;
		joglesctx.glLightSource[lightSlot].linearAttenuation = 0.0f;
		joglesctx.glLightSource[lightSlot].quadraticAttenuation = 0.0f;
		joglesctx.glLightSource[lightSlot].spotExponent = 0.0f;
		joglesctx.glLightSource[lightSlot].spotCutoff = 180.0f;
	}

	// ---------------------------------------------------------------------

	//
	// PointLightRetained methods
	//
	@Override
	void updatePointLight(Context ctx, int lightSlot, float red, float green, float blue, float attenx, float atteny, float attenz,
			float posx, float posy, float posz)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updatePointLight()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updatePointLight++;

		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);

		// note the current state of MV MUST be used by the lights when setting position!
		// https://www.opengl.org/discussion_boards/showthread.php/168706-Light-Position-in-eye-s-cordinate

		// note can't use the modelview as it's calced late		
		Vector4f lightPos = joglesctx.matrixUtil.transform(joglesctx.currentModelMat, joglesctx.currentViewMat, posx, posy, posz, 1.0f);

		if (joglesctx.glLightSource[lightSlot] == null)
		{
			joglesctx.glLightSource[lightSlot] = new glLightSource();
			joglesctx.gl_state.glLightSource[lightSlot] = null;
		}

		joglesctx.glLightSource[lightSlot].diffuse.x = red;
		joglesctx.glLightSource[lightSlot].diffuse.y = green;
		joglesctx.glLightSource[lightSlot].diffuse.z = blue;
		joglesctx.glLightSource[lightSlot].diffuse.w = 1.0f;
		joglesctx.glLightSource[lightSlot].specular.x = red;
		joglesctx.glLightSource[lightSlot].specular.y = green;
		joglesctx.glLightSource[lightSlot].specular.z = blue;
		joglesctx.glLightSource[lightSlot].specular.w = 1.0f;
		joglesctx.glLightSource[lightSlot].position.x = lightPos.x;
		joglesctx.glLightSource[lightSlot].position.y = lightPos.y;
		joglesctx.glLightSource[lightSlot].position.z = lightPos.z;
		joglesctx.glLightSource[lightSlot].position.w = 1.0f;// 1 mean pos not dir
		//joglesctx.pointLight[lightSlot].ambient = black;// odd
		joglesctx.glLightSource[lightSlot].constantAttenuation = attenx;
		joglesctx.glLightSource[lightSlot].linearAttenuation = atteny;
		joglesctx.glLightSource[lightSlot].quadraticAttenuation = attenz;
		joglesctx.glLightSource[lightSlot].spotExponent = 0.0f;
		joglesctx.glLightSource[lightSlot].spotCutoff = 180.0f;
	}

	// ---------------------------------------------------------------------

	//
	// SpotLightRetained methods
	//
	@Override
	void updateSpotLight(Context ctx, int lightSlot, float red, float green, float blue, float attenx, float atteny, float attenz,
			float posx, float posy, float posz, float spreadAngle, float concentration, float dirx, float diry, float dirz)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateSpotLight()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updateSpotLight++;

		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);

		// note the current state of MV MUST be used by the lights when setting position!
		//https://www.opengl.org/discussion_boards/showthread.php/168706-Light-Position-in-eye-s-cordinate

		// note can't use the modelview as it's  calced late
		Vector4f lightPos = joglesctx.matrixUtil.transform(joglesctx.currentModelMat, joglesctx.currentViewMat, posx, posy, posz, 1.0f);

		if (joglesctx.glLightSource[lightSlot] == null)
		{
			joglesctx.glLightSource[lightSlot] = new glLightSource();
			joglesctx.gl_state.glLightSource[lightSlot] = null;
		}

		joglesctx.glLightSource[lightSlot].diffuse.x = red;
		joglesctx.glLightSource[lightSlot].diffuse.y = green;
		joglesctx.glLightSource[lightSlot].diffuse.z = blue;
		joglesctx.glLightSource[lightSlot].diffuse.w = 1.0f;
		joglesctx.glLightSource[lightSlot].specular.x = red;
		joglesctx.glLightSource[lightSlot].specular.y = green;
		joglesctx.glLightSource[lightSlot].specular.z = blue;
		joglesctx.glLightSource[lightSlot].specular.w = 1.0f;
		joglesctx.glLightSource[lightSlot].position.x = lightPos.x;
		joglesctx.glLightSource[lightSlot].position.y = lightPos.y;
		joglesctx.glLightSource[lightSlot].position.z = lightPos.z;
		joglesctx.glLightSource[lightSlot].position.w = 1.0f;// 1 mean pos not dir
		//joglesctx.glLightSource[lightSlot].ambient = black;// odd
		joglesctx.glLightSource[lightSlot].constantAttenuation = attenx;
		joglesctx.glLightSource[lightSlot].linearAttenuation = atteny;
		joglesctx.glLightSource[lightSlot].quadraticAttenuation = attenz;
		joglesctx.glLightSource[lightSlot].spotDirection.x = dirx;
		joglesctx.glLightSource[lightSlot].spotDirection.y = diry;
		joglesctx.glLightSource[lightSlot].spotDirection.z = dirz;
		joglesctx.glLightSource[lightSlot].spotExponent = concentration;
		joglesctx.glLightSource[lightSlot].spotCutoff = (float) (spreadAngle * 180.0f / Math.PI);

	}

	// ---------------------------------------------------------------------

	//
	// ExponentialFogRetained methods
	//
	@Override
	void updateExponentialFog(Context ctx, float red, float green, float blue, float density)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateExponentialFog()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updateExponentialFog++;

		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		joglesctx.gl_state.fogData.expColor.x = Float.NEGATIVE_INFINITY;
		joglesctx.fogData.expColor.x = red;
		joglesctx.fogData.expColor.y = green;
		joglesctx.fogData.expColor.z = blue;
		joglesctx.fogData.expDensity = density;
		joglesctx.fogData.fogEnabled = 1;
	}

	// ---------------------------------------------------------------------

	//
	// LinearFogRetained methods
	//
	@Override
	void updateLinearFog(Context ctx, float red, float green, float blue, double fdist, double bdist)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateLinearFog()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updateLinearFog++;

		// see
		// https://www.opengl.org/discussion_boards/showthread.php/151415-Fog-with-pixel-shader-%28arb_fragment_program%29

		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		joglesctx.gl_state.fogData.expColor.x = Float.NEGATIVE_INFINITY;
		joglesctx.fogData.linearColor.x = red;
		joglesctx.fogData.linearColor.y = green;
		joglesctx.fogData.linearColor.z = blue;
		joglesctx.fogData.linearStart = (float) fdist;
		joglesctx.fogData.linearEnd = (float) bdist;
		joglesctx.fogData.fogEnabled = 1;
	}

	// native method for disabling fog
	@Override
	void disableFog(Context ctx)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.disableFog()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.disableFog++;

		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		joglesctx.gl_state.fogData.expColor.x = Float.NEGATIVE_INFINITY;
		joglesctx.fogData.fogEnabled = 0;
	}

	// native method for setting fog enable flag
	@Override
	void setFogEnableFlag(Context ctx, boolean enable)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setFogEnableFlag()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.setFogEnableFlag++;

		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		joglesctx.gl_state.fogData.expColor.x = Float.NEGATIVE_INFINITY;
		joglesctx.fogData.fogEnabled = enable ? 1 : 0;
	}

	// ---------------------------------------------------------------------

	//
	// LineAttributesRetained methods
	//
	@Override
	void updateLineAttributes(Context ctx, float lineWidth, int linePattern, int linePatternMask, int linePatternScaleFactor,
			boolean lineAntialiasing)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateLineAttributes()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updateLineAttributes++;

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glLineWidth(lineWidth);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

	}

	// native method for setting default LineAttributes
	@Override
	void resetLineAttributes(Context ctx)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.resetLineAttributes()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.resetLineAttributes++;

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		gl.glLineWidth(1.0f);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
	}

	// ---------------------------------------------------------------------

	//
	// MaterialRetained methods
	//
	@Override
	void updateMaterial(Context ctx, float red, float green, float blue, float alpha, float aRed, float aGreen, float aBlue, float eRed,
			float eGreen, float eBlue, float dRed, float dGreen, float dBlue, float sRed, float sGreen, float sBlue, float shininess,
			int colorTarget, boolean lightEnable)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateMaterial()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updateMaterial++;

		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		// will we need to repush to shaders?
		if (joglesctx.objectColor.x != red || joglesctx.objectColor.y != green || joglesctx.objectColor.z != blue
				|| joglesctx.objectColor.w != alpha){
			joglesctx.gl_state.objectColor.x = Float.NEGATIVE_INFINITY;
		joglesctx.objectColor.x = red;
		joglesctx.objectColor.y = green;
		joglesctx.objectColor.z = blue;
		joglesctx.objectColor.w = alpha;}

		joglesctx.materialData.lightEnabled = lightEnable ? 1 : 0;
		joglesctx.materialData.shininess = shininess;
		if (joglesctx.materialData.emission.x != eRed || joglesctx.materialData.emission.y != eGreen
				|| joglesctx.materialData.emission.z != eBlue){
			joglesctx.gl_state.glFrontMaterial.emission.x = Float.NEGATIVE_INFINITY;
		joglesctx.materialData.emission.x = eRed;
		joglesctx.materialData.emission.y = eGreen;
		joglesctx.materialData.emission.z = eBlue;}
		if (joglesctx.materialData.ambient.x != aRed || joglesctx.materialData.ambient.y != aGreen
				|| joglesctx.materialData.ambient.z != aBlue){
			joglesctx.gl_state.glFrontMaterial.ambient.x = Float.NEGATIVE_INFINITY;
		joglesctx.materialData.ambient.x = aRed;
		joglesctx.materialData.ambient.y = aGreen;
		joglesctx.materialData.ambient.z = aBlue;}
		if (joglesctx.materialData.specular.x != sRed || joglesctx.materialData.specular.y != sGreen
				|| joglesctx.materialData.specular.z != sBlue){
			joglesctx.gl_state.glFrontMaterial.specular.x = Float.NEGATIVE_INFINITY;
		joglesctx.materialData.specular.x = sRed;
		joglesctx.materialData.specular.y = sGreen;
		joglesctx.materialData.specular.z = sBlue;}
		if (joglesctx.materialData.diffuse.x != dRed || joglesctx.materialData.diffuse.y != dGreen
				|| joglesctx.materialData.diffuse.z != dBlue || joglesctx.materialData.diffuse.w != alpha){
			joglesctx.gl_state.glFrontMaterial.diffuse.x = Float.NEGATIVE_INFINITY;
		joglesctx.materialData.diffuse.x = dRed;
		joglesctx.materialData.diffuse.y = dGreen;
		joglesctx.materialData.diffuse.z = dBlue;
		joglesctx.materialData.diffuse.w = alpha;}
	}

	// native method for setting Material when no material is present
	@Override
	void updateMaterialColor(Context ctx, float r, float g, float b, float a)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateMaterialColor()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updateMaterialColor++;

		// update single color in case where material has color and there are no coloring attributes
		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		if (joglesctx.objectColor.x != r || joglesctx.objectColor.y != g || joglesctx.objectColor.z != b || joglesctx.objectColor.w != a)
			joglesctx.gl_state.objectColor.x = Float.NEGATIVE_INFINITY;
		joglesctx.objectColor.x = r;
		joglesctx.objectColor.y = g;
		joglesctx.objectColor.z = b;
		joglesctx.objectColor.w = a;

	}
	// ---------------------------------------------------------------------

	//
	// ColoringAttributesRetained methods
	@Override
	void updateColoringAttributes(Context ctx, float dRed, float dGreen, float dBlue, float red, float green, float blue, float alpha,
			boolean lightEnable, int shadeModel)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateColoringAttributes()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updateColoringAttributes++;

		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		// note we ignore lightEnabled and always pass the object color to the shader if it wants it
		if (joglesctx.objectColor.x != red || joglesctx.objectColor.y != green || joglesctx.objectColor.z != blue
				|| joglesctx.objectColor.w != alpha)
			joglesctx.gl_state.objectColor.x = Float.NEGATIVE_INFINITY;
		joglesctx.objectColor.x = red;
		joglesctx.objectColor.y = green;
		joglesctx.objectColor.z = blue;
		joglesctx.objectColor.w = alpha;

		//TODO: why is enable light ignored?

	}

	// native method for setting default ColoringAttributes
	@Override
	void resetColoringAttributes(Context ctx, float r, float g, float b, float a, boolean enableLight)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.resetColoringAttributes() " + r + " " + g + " " + b + " " + a + " " + enableLight);
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.resetColoringAttributes++;

		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		if (joglesctx.objectColor.x != r || joglesctx.objectColor.y != g || joglesctx.objectColor.z != b || joglesctx.objectColor.w != a)
			joglesctx.gl_state.objectColor.x = Float.NEGATIVE_INFINITY;
		joglesctx.objectColor.x = r;
		joglesctx.objectColor.y = g;
		joglesctx.objectColor.z = b;
		joglesctx.objectColor.w = a;

		//TODO: why is enable light ignored?
	}

	// ---------------------------------------------------------------------

	//
	// PointAttributesRetained methods
	//
	// interesting as Points are how particles are done properly!!
	// http://stackoverflow.com/questions/3497068/textured-points-in-opengl-es-2-0
	// http://stackoverflow.com/questions/7237086/opengl-es-2-0-equivalent-for-es-1-0-circles-using-gl-point-smooth
	@Override
	void updatePointAttributes(Context ctx, float pointSize, boolean pointAntialiasing)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updatePointAttributes()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updatePointAttributes++;

		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		joglesctx.pointSize = pointSize;

		// one time enable call
		if (!pointsEnabled)
		{
			GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
			// bug in desktop requiring this to be set still
			gl.glEnable(0x8642);// GL_VERTEX_PROGRAM_POINT_SIZE
			gl.glEnable(34913);// GL.GL_POINT_SPRITE);
			pointsEnabled = true;
		}
	}

	private boolean pointsEnabled = false;

	// native method for setting default PointAttributes
	@Override
	void resetPointAttributes(Context ctx)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.resetPointAttributes()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.resetPointAttributes++;

		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		joglesctx.pointSize = 1.0f;
	}

	// ---------------------------------------------------------------------

	//
	// PolygonAttributesRetained methods
	//
	@Override
	void updatePolygonAttributes(Context ctx, int polygonMode, int cullFace, boolean backFaceNormalFlip, float polygonOffset,
			float polygonOffsetFactor)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updatePolygonAttributes()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updatePolygonAttributes++;

		GL2ES2 gl = null;// get only if needed, expensive call
		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		if (joglesctx.gl_state.cullFace != cullFace)
		{
			gl = ((Jogl2es2Context) ctx).gl2es2();
			if (cullFace == PolygonAttributes.CULL_NONE)
			{
				gl.glDisable(GL2ES2.GL_CULL_FACE);
			}
			else
			{
				if (cullFace == PolygonAttributes.CULL_BACK)
				{
					gl.glCullFace(GL2ES2.GL_BACK);
				}
				else
				{
					gl.glCullFace(GL2ES2.GL_FRONT);
				}
				gl.glEnable(GL2ES2.GL_CULL_FACE);
			}
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);

			if (MINIMISE_NATIVE_CALLS_OTHER)
				joglesctx.gl_state.cullFace = cullFace;
		}

		if (joglesctx.gl_state.polygonOffsetFactor != polygonOffsetFactor || joglesctx.gl_state.polygonOffset != polygonOffset)
		{
			gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
			gl.glPolygonOffset(polygonOffsetFactor, polygonOffset);

			if ((polygonOffsetFactor != 0.0f) || (polygonOffset != 0.0f))
			{
				gl.glEnable(GL2ES2.GL_POLYGON_OFFSET_FILL);
			}
			else
			{
				gl.glDisable(GL2ES2.GL_POLYGON_OFFSET_FILL);
			}
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
			if (MINIMISE_NATIVE_CALLS_OTHER)
			{
				joglesctx.gl_state.polygonOffsetFactor = polygonOffsetFactor;
				joglesctx.gl_state.polygonOffset = polygonOffset;
			}
		}
		joglesctx.polygonMode = polygonMode;
	}

	// native method for setting default PolygonAttributes
	@Override
	void resetPolygonAttributes(Context ctx)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.resetPolygonAttributes()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.resetPolygonAttributes++;

		GL2ES2 gl = null;// get only if needed, expensive call
		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		if (joglesctx.gl_state.cullFace != PolygonAttributes.CULL_BACK)
		{
			gl = ((Jogl2es2Context) ctx).gl2es2();
			gl.glCullFace(GL2ES2.GL_BACK);
			gl.glEnable(GL2ES2.GL_CULL_FACE);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
			if (MINIMISE_NATIVE_CALLS_OTHER)
				joglesctx.gl_state.cullFace = PolygonAttributes.CULL_BACK;
		}

		if (joglesctx.gl_state.polygonOffsetFactor != 0.0f || joglesctx.gl_state.polygonOffset != 0.0f)
		{
			gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
			gl.glPolygonOffset(0.0f, 0.0f);
			gl.glDisable(GL2ES2.GL_POLYGON_OFFSET_FILL);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
			if (MINIMISE_NATIVE_CALLS_OTHER)
			{
				joglesctx.gl_state.polygonOffsetFactor = 0.0f;
				joglesctx.gl_state.polygonOffset = 0.0f;
			}
		}

		joglesctx.polygonMode = PolygonAttributes.POLYGON_FILL;
	}

	// ---------------------------------------------------------------------

	//
	// RenderingAttributesRetained methods
	//
	@Override
	void updateRenderingAttributes(Context ctx, boolean depthBufferWriteEnableOverride, boolean depthBufferEnableOverride,
			boolean depthBufferEnable, boolean depthBufferWriteEnable, int depthTestFunction, float alphaTestValue, int alphaTestFunction,
			boolean ignoreVertexColors, boolean rasterOpEnable, int rasterOp, boolean userStencilAvailable, boolean stencilEnable,
			int stencilFailOp, int stencilZFailOp, int stencilZPassOp, int stencilFunction, int stencilReferenceValue,
			int stencilCompareMask, int stencilWriteMask)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateRenderingAttributes()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updateRenderingAttributes++;

		GL2ES2 gl = null;// get late expensive

		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		if (joglesctx.gl_state.depthBufferEnableOverride != depthBufferEnable || joglesctx.gl_state.depthBufferEnable != depthBufferEnable
				|| joglesctx.gl_state.depthTestFunction != depthTestFunction)
		{
			if (!depthBufferEnableOverride)
			{
				gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
				if (depthBufferEnable)
				{
					gl.glEnable(GL2ES2.GL_DEPTH_TEST);
					gl.glDepthFunc(getFunctionValue(depthTestFunction));
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);
				}
				else
				{
					gl.glDisable(GL2ES2.GL_DEPTH_TEST);
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);
				}
			}

			if (MINIMISE_NATIVE_CALLS_OTHER)
				joglesctx.gl_state.depthBufferEnableOverride = depthBufferEnable;
			if (MINIMISE_NATIVE_CALLS_OTHER)
				joglesctx.gl_state.depthBufferEnable = depthBufferEnable;
			if (MINIMISE_NATIVE_CALLS_OTHER)
				joglesctx.gl_state.depthTestFunction = depthTestFunction;
		}

		if (!depthBufferWriteEnableOverride)
		{
			if (depthBufferWriteEnable)
			{
				if (joglesctx.gl_state.glDepthMask != true)
				{
					gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
					gl.glDepthMask(true);
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);
					if (MINIMISE_NATIVE_CALLS_OTHER)
						joglesctx.gl_state.glDepthMask = true;
				}
			}
			else
			{
				if (joglesctx.gl_state.glDepthMask != false)
				{
					gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
					gl.glDepthMask(false);
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);
					if (MINIMISE_NATIVE_CALLS_OTHER)
						joglesctx.gl_state.glDepthMask = true;
				}
			}
		}

		if (alphaTestFunction == RenderingAttributes.ALWAYS)
		{
			joglesctx.renderingData.alphaTestEnabled = false;
		}
		else
		{
			joglesctx.renderingData.alphaTestEnabled = true;
			joglesctx.renderingData.alphaTestFunction = getFunctionValue(alphaTestFunction);
			joglesctx.renderingData.alphaTestValue = alphaTestValue;
		}

		joglesctx.renderingData.ignoreVertexColors = ignoreVertexColors ? 1 : 0;

		if (rasterOpEnable)
		{
			System.err.println("rasterOpEnable!!!! no no no!");
		}

		if (userStencilAvailable)
		{
			if (stencilEnable)
			{
				if (joglesctx.gl_state.glEnableGL_STENCIL_TEST == false || joglesctx.gl_state.stencilFailOp != stencilFailOp
						|| joglesctx.gl_state.stencilZFailOp != stencilZFailOp || joglesctx.gl_state.stencilZPassOp != stencilZPassOp
						|| joglesctx.gl_state.stencilFunction != stencilFunction
						|| joglesctx.gl_state.stencilReferenceValue != stencilReferenceValue
						|| joglesctx.gl_state.stencilCompareMask != stencilCompareMask
						|| joglesctx.gl_state.stencilWriteMask != stencilWriteMask)
				{
					gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
					gl.glEnable(GL2ES2.GL_STENCIL_TEST);
					gl.glStencilOp(getStencilOpValue(stencilFailOp), getStencilOpValue(stencilZFailOp), getStencilOpValue(stencilZPassOp));
					gl.glStencilFunc(getFunctionValue(stencilFunction), stencilReferenceValue, stencilCompareMask);
					gl.glStencilMask(stencilWriteMask);
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);
					if (MINIMISE_NATIVE_CALLS_OTHER)
						joglesctx.gl_state.glEnableGL_STENCIL_TEST = true;
				}
			}
			else
			{
				if (joglesctx.gl_state.glEnableGL_STENCIL_TEST == true)
				{
					gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
					gl.glDisable(GL2ES2.GL_STENCIL_TEST);
					if (DO_OUTPUT_ERRORS)
						outputErrors(ctx);
					if (MINIMISE_NATIVE_CALLS_OTHER)
						joglesctx.gl_state.glEnableGL_STENCIL_TEST = false;
				}
			}
		}

	}

	// native method for setting default RenderingAttributes
	@Override
	void resetRenderingAttributes(Context ctx, boolean depthBufferWriteEnableOverride, boolean depthBufferEnableOverride)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.resetRenderingAttributes()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.resetRenderingAttributes++;

		GL2ES2 gl = null; // get late expensive
		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		if (!depthBufferWriteEnableOverride)
		{
			if (joglesctx.gl_state.glDepthMask != true)
			{
				gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
				gl.glDepthMask(true);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_OTHER)
					joglesctx.gl_state.glDepthMask = true;
			}
		}
		if (!depthBufferEnableOverride)
		{
			if (joglesctx.gl_state.depthBufferEnable != true)
			{
				gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
				gl.glEnable(GL2ES2.GL_DEPTH_TEST);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_OTHER)
					joglesctx.gl_state.depthBufferEnable = true;
			}
		}
		if (joglesctx.gl_state.depthTestFunction != RenderingAttributes.LESS_OR_EQUAL)
		{
			gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
			gl.glDepthFunc(GL2ES2.GL_LEQUAL);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
			if (MINIMISE_NATIVE_CALLS_OTHER)
				joglesctx.gl_state.depthTestFunction = RenderingAttributes.LESS_OR_EQUAL;
		}

		joglesctx.renderingData.alphaTestEnabled = false;
		joglesctx.renderingData.alphaTestFunction = RenderingAttributes.ALWAYS;
		joglesctx.renderingData.alphaTestValue = 0;
		joglesctx.renderingData.ignoreVertexColors = 0;

		if (joglesctx.gl_state.glEnableGL_STENCIL_TEST == true)
		{
			gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
			gl.glDisable(GL2ES2.GL_STENCIL_TEST);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
			if (MINIMISE_NATIVE_CALLS_OTHER)
				joglesctx.gl_state.glEnableGL_STENCIL_TEST = false;
		}

	}

	@Override
	void updateTransparencyAttributes(Context ctx, float alpha, int geometryType, int polygonMode, boolean lineAA, boolean pointAA,
			int transparencyMode, int srcBlendFunction, int dstBlendFunction)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateTransparencyAttributes() " + alpha + " " + geometryType + " " + polygonMode + " "
					+ lineAA + " " + pointAA + " " + transparencyMode + " " + srcBlendFunction + " " + dstBlendFunction);
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updateTransparencyAttributes++;

		GL2ES2 gl = null;//get late expensive
		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);

		joglesctx.transparencyAlpha = alpha;

		if ((transparencyMode < TransparencyAttributes.SCREEN_DOOR)
				|| ((((geometryType & RenderMolecule.LINE) != 0) || (polygonMode == PolygonAttributes.POLYGON_LINE)) && lineAA)
				|| ((((geometryType & RenderMolecule.POINT) != 0) || (polygonMode == PolygonAttributes.POLYGON_POINT)) && pointAA))
		{
			if (!MINIMISE_NATIVE_CALLS_TRANSPARENCY || (joglesctx.gl_state.glEnableGL_BLEND != true
					|| joglesctx.gl_state.srcBlendFunction != srcBlendFunction || joglesctx.gl_state.dstBlendFunction != dstBlendFunction))
			{
				gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
				gl.glEnable(GL2ES2.GL_BLEND);
				// valid range of blendFunction 0..3 is already verified in shared code.
				gl.glBlendFunc(blendFunctionTable[srcBlendFunction], blendFunctionTable[dstBlendFunction]);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

				if (MINIMISE_NATIVE_CALLS_TRANSPARENCY)
				{
					joglesctx.gl_state.glEnableGL_BLEND = true;
					joglesctx.gl_state.srcBlendFunction = srcBlendFunction;
					joglesctx.gl_state.dstBlendFunction = dstBlendFunction;
				}
			}

		}
		else
		{
			if (!MINIMISE_NATIVE_CALLS_TRANSPARENCY || (joglesctx.gl_state.glEnableGL_BLEND != false))
			{
				gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
				gl.glDisable(GL2ES2.GL_BLEND);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_TRANSPARENCY)
					joglesctx.gl_state.glEnableGL_BLEND = false;
			}
		}

	}

	// native method for setting default TransparencyAttributes
	@Override
	void resetTransparency(Context ctx, int geometryType, int polygonMode, boolean lineAA, boolean pointAA)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.resetTransparency()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.resetTransparency++;

		GL2ES2 gl = null; //get late expensive
		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);
		
		joglesctx.transparencyAlpha = 1.0f;
		
		if (((((geometryType & RenderMolecule.LINE) != 0) || (polygonMode == PolygonAttributes.POLYGON_LINE)) && lineAA)
				|| ((((geometryType & RenderMolecule.POINT) != 0) || (polygonMode == PolygonAttributes.POLYGON_POINT)) && pointAA))
		{
			if (!MINIMISE_NATIVE_CALLS_TRANSPARENCY || (joglesctx.gl_state.glEnableGL_BLEND != true
					|| joglesctx.gl_state.srcBlendFunction != TransparencyAttributes.BLEND_SRC_ALPHA
					|| joglesctx.gl_state.dstBlendFunction != TransparencyAttributes.BLEND_ONE_MINUS_SRC_ALPHA))
			{
				gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
				gl.glEnable(GL2ES2.GL_BLEND);
				gl.glBlendFunc(GL2ES2.GL_SRC_ALPHA, GL2ES2.GL_ONE_MINUS_SRC_ALPHA);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_TRANSPARENCY)
				{
					joglesctx.gl_state.glEnableGL_BLEND = true;
					joglesctx.gl_state.srcBlendFunction = TransparencyAttributes.BLEND_SRC_ALPHA;
					joglesctx.gl_state.dstBlendFunction = TransparencyAttributes.BLEND_ONE_MINUS_SRC_ALPHA;
				}
			}
		}
		else
		{
			if (!MINIMISE_NATIVE_CALLS_TRANSPARENCY || (joglesctx.gl_state.glEnableGL_BLEND != false))
			{
				gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
				gl.glDisable(GL2ES2.GL_BLEND);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_TRANSPARENCY)
					joglesctx.gl_state.glEnableGL_BLEND = false;
			}
		}
	}

	//
	// TextureAttributesRetained methods
	//
	@Override
	void updateTextureAttributes(Context ctx, double[] transform, boolean isIdentity, int textureMode, int perspCorrectionMode,
			float textureBlendColorRed, float textureBlendColorGreen, float textureBlendColorBlue, float textureBlendColorAlpha,
			int textureFormat)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateTextureAttributes() " + lineString(transform));
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updateTextureAttributes++;

		Jogl2es2Context joglesctx = (Jogl2es2Context) ctx;
		joglesctx.gl_state.textureTransform.m00 = Double.NEGATIVE_INFINITY;
		joglesctx.textureTransform.set(transform);
	}

	// native method for setting default TextureAttributes
	@Override
	void resetTextureAttributes(Context ctx)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.resetTextureAttributes()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.resetTextureAttributes++;

		// set Identity
		Jogl2es2Context joglesctx = (Jogl2es2Context) ctx;
		joglesctx.gl_state.textureTransform.m00 = Double.NEGATIVE_INFINITY;
		joglesctx.textureTransform.setIdentity();

	}

	// ---------------------------------------------------------------------

	// native method for setting default TexCoordGeneration -Noop
	@Override
	void resetTexCoordGeneration(Context ctx)
	{
		// TexCoordGeneration must be done in shaders
		// if (VERBOSE)
		// System.err.println("JoglPipeline.resetTexCoordGeneration()");
		// if (OUTPUT_PER_FRAME_STATS)
		// ((JoglesContext) ctx).perFrameStats.resetTexCoordGeneration++;
	}

	// ---------------------------------------------------------------------

	//
	// TextureUnitStateRetained methods
	//
	@Override
	void updateTextureUnitState(Context ctx, int index, boolean enable)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateTextureUnitState(index=" + index + ",enable=" + enable + ")");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.updateTextureUnitState++;

		Jogl2es2Context joglesContext = (Jogl2es2Context) ctx;
		GL2ES2 gl = null;//get late expensive

		if (index >= 0)
		{
			if (!MINIMISE_NATIVE_CALLS_TEXTURE || (joglesContext.gl_state.glActiveTexture != (index + GL2ES2.GL_TEXTURE0)))
			{
				gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
				gl.glActiveTexture(index + GL2ES2.GL_TEXTURE0);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_TEXTURE)
					joglesContext.gl_state.glActiveTexture = (index + GL2ES2.GL_TEXTURE0);
			}
		}
	}

	// ---------------------------------------------------------------------

	//
	// TextureRetained methods
	// Texture2DRetained methods
	//
	@Override
	void bindTexture2D(Context ctx, int objectId, boolean enable)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.bindTexture2D(objectId=" + objectId + ",enable=" + enable + ")");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.bindTexture2D++;

		Jogl2es2Context joglesContext = (Jogl2es2Context) ctx;
		GL2ES2 gl = null;// get late expensive

		if (enable)
		{
			if (!MINIMISE_NATIVE_CALLS_TEXTURE
					|| (joglesContext.gl_state.glBindTextureGL_TEXTURE_2D[joglesContext.gl_state.glActiveTexture] != objectId))
			{
				gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
				gl.glBindTexture(GL2ES2.GL_TEXTURE_2D, objectId);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);

				if (MINIMISE_NATIVE_CALLS_TEXTURE)
					joglesContext.gl_state.glBindTextureGL_TEXTURE_2D[joglesContext.gl_state.glActiveTexture] = objectId;
			}
		}
	}

	@Override
	void updateTexture2DImage(Context ctx, int numLevels, int level, int textureFormat, int imageFormat, int width, int height,
			int boundaryWidth, int dataType, Object data, boolean useAutoMipMap)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateTexture2DImage(width=" + width + ",height=" + height + ",level=" + level + ")");

		updateTexture2DImage(ctx, GL2ES2.GL_TEXTURE_2D, numLevels, level, textureFormat, imageFormat, width, height, boundaryWidth,
				dataType, data, useAutoMipMap);
	}

	// oddly in use when I press escape twice???
	@Override
	void updateTexture2DSubImage(Context ctx, int level, int xoffset, int yoffset, int textureFormat, int imageFormat, int imgXOffset,
			int imgYOffset, int tilew, int width, int height, int dataType, Object data, boolean useAutoMipMap)
	{

		// Note: useAutoMipMap is not use for SubImage in the jogl pipe 

		if (VERBOSE)
			System.err.println("JoglPipeline.updateTexture2DSubImage()");

		updateTexture2DSubImage(ctx, GL2ES2.GL_TEXTURE_2D, level, xoffset, yoffset, textureFormat, imageFormat, imgXOffset, imgYOffset,
				tilew, width, height, dataType, data);
	}

	@Override
	void updateTexture2DLodRange(Context ctx, int baseLevel, int maximumLevel, float minimumLOD, float maximumLOD)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateTexture2DLodRange()");

		updateTextureLodRange(ctx, GL2ES2.GL_TEXTURE_2D, baseLevel, maximumLevel, minimumLOD, maximumLOD);
	}

	@Override
	void updateTexture2DBoundary(Context ctx, int boundaryModeS, int boundaryModeT, float boundaryRed, float boundaryGreen,
			float boundaryBlue, float boundaryAlpha)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateTexture2DBoundary()");

		updateTextureBoundary(ctx, GL2ES2.GL_TEXTURE_2D, boundaryModeS, boundaryModeT, -1, boundaryRed, boundaryGreen, boundaryBlue,
				boundaryAlpha);
	}

	@Override
	void updateTexture2DFilterModes(Context ctx, int minFilter, int magFilter)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateTexture2DFilterModes()");

		updateTextureFilterModes(ctx, GL2ES2.GL_TEXTURE_2D, minFilter, magFilter);
	}

	@Override
	void updateTexture2DAnisotropicFilter(Context ctx, float degree)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateTexture2DAnisotropicFilter()");

		updateTextureAnisotropicFilter(ctx, GL2ES2.GL_TEXTURE_2D, degree);
	}

	private static void updateTextureLodRange(Context ctx, int target, int baseLevel, int maximumLevel, float minimumLOD, float maximumLOD)
	{
		//GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		// I notice these 4 parameters don't appear under GL2ES2

		// target is good, pname can't have any of the 4 given, though I wonder if this is an extension somehow?
		// confirmed here http://stackoverflow.com/questions/34499219/correct-way-to-unbind-an-open-gl-es-texture

		// target Specifies the target texture of the active texture unit,
		// which must be either GL_TEXTURE_2D or GL_TEXTURE_CUBE_MAP.
		// pname Specifies the symbolic name of a single-valued texture/ parameter.
		// pname can be one of the following: GL_TEXTURE_MIN_FILTER, GL_TEXTURE_MAG_FILTER,
		// GL_TEXTURE_WRAP_S, or GL_TEXTURE_WRAP_T.

		// checking of the availability of the extension is already done
		// in the shared code
		// Apparently these are available in ES3

		// gl.glTexParameteri(target, GL2ES3.GL_TEXTURE_BASE_LEVEL, baseLevel);

		// http://stackoverflow.com/questions/12767917/is-using-gl-nearest-mipmap-or-gl-linear-mipmap-for-gl-texture-min-filter-con
		// ES2 throws a 1280 invalid enum here

		//if (gl.isGL2ES3())
		//	gl.glTexParameteri(target, GL2ES3.GL_TEXTURE_MAX_LEVEL, maximumLevel);
		// gl.glTexParameterf(target, GL2ES3.GL_TEXTURE_MIN_LOD, minimumLOD);
		// gl.glTexParameterf(target, GL2ES3.GL_TEXTURE_MAX_LOD, maximumLOD);

		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
	}

	private static void updateTextureAnisotropicFilter(Context ctx, int target, float degree)
	{
		// FIXME: is this a true thing to send in?
		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		// it appears GL_TEXTURE_MAX_ANISOTROPY_EXT is still part of ES2
		// but not allowed for glTexParameterf
		// here http://www.informit.com/articles/article.aspx?p=770639&seqNum=2 suggest the
		// parameter may still be passed if the EXT is enabled

		// checking of the availability of anisotropic filter functionality
		// is already done in the shared code
		gl.glTexParameterf(target, GL2ES2.GL_TEXTURE_MAX_ANISOTROPY_EXT, degree);

		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
	}

	// ---------------------------------------------------------------------

	//
	// TextureCubeMapRetained methods
	//
	@Override
	void bindTextureCubeMap(Context ctx, int objectId, boolean enable)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.bindTextureCubeMap()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.bindTextureCubeMap++;

		GL2ES2 gl = null;// get late expensive

		// TextureCubeMap will take precedents over 3D Texture so
		// there is no need to disable 3D Texture here.
		if (!enable)
		{
			// gl.glDisable(GL2ES2.GL_TEXTURE_CUBE_MAP);
		}
		else
		{
			gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
			gl.glBindTexture(GL2ES2.GL_TEXTURE_CUBE_MAP, objectId);
			// gl.glEnable(GL2ES2.GL_TEXTURE_CUBE_MAP);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}
	}

	@Override
	void updateTextureCubeMapImage(Context ctx, int face, int numLevels, int level, int textureFormat, int imageFormat, int width,
			int height, int boundaryWidth, int dataType, Object data, boolean useAutoMipMap)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateTextureCubeMapImage()");

		updateTexture2DImage(ctx, _gl_textureCubeMapFace[face], numLevels, level, textureFormat, imageFormat, width, height, boundaryWidth,
				dataType, data, useAutoMipMap);
	}

	@Override
	void updateTextureCubeMapSubImage(Context ctx, int face, int level, int xoffset, int yoffset, int textureFormat, int imageFormat,
			int imgXOffset, int imgYOffset, int tilew, int width, int height, int dataType, Object data, boolean useAutoMipMap)
	{
		// PJ why is this the case? 
		throw new UnsupportedOperationException();
	}

	@Override
	void updateTextureCubeMapLodRange(Context ctx, int baseLevel, int maximumLevel, float minimumLod, float maximumLod)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateTextureCubeMapLodRange()");

		updateTextureLodRange(ctx, GL2ES2.GL_TEXTURE_CUBE_MAP, baseLevel, maximumLevel, minimumLod, maximumLod);
	}

	@Override
	void updateTextureCubeMapBoundary(Context ctx, int boundaryModeS, int boundaryModeT, float boundaryRed, float boundaryGreen,
			float boundaryBlue, float boundaryAlpha)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateTextureCubeMapBoundary()");

		updateTextureBoundary(ctx, GL2ES2.GL_TEXTURE_CUBE_MAP, boundaryModeS, boundaryModeT, -1, boundaryRed, boundaryGreen, boundaryBlue,
				boundaryAlpha);
	}

	@Override
	void updateTextureCubeMapFilterModes(Context ctx, int minFilter, int magFilter)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateTextureCubeMapFilterModes()");

		updateTextureFilterModes(ctx, GL2ES2.GL_TEXTURE_CUBE_MAP, minFilter, magFilter);
	}

	@Override
	void updateTextureCubeMapAnisotropicFilter(Context ctx, float degree)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateTextureCubeMapAnisotropicFilter()");

		updateTextureAnisotropicFilter(ctx, GL2ES2.GL_TEXTURE_CUBE_MAP, degree);
	}

	// ----------------------------------------------------------------------
	//
	// Helper routines for above texture methods
	//

	private void updateTexture2DImage(Context ctx, int target, int numLevels, int level, int textureFormat, int imageFormat, int width,
			int height, int boundaryWidth, int dataType, Object data, boolean useAutoMipMap)
	{
		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		// FIXME: there is a new call glGenerateMipmap() which is only in ES2 not GL2 so on pure ES2 
		// add back in checking for mipmap support under properties, then add that call after bind texture
		// which mean the gl.glTexParameteri(target, GL2ES2.GL_GENERATE_MIPMAP, GL2ES2.GL_TRUE); are gone
		//the extensions need to have an ES2 version
		// the glPixelTransferf alpha madness which I don't understand can be deleted along with the texture formats
		// the other look like they are ok, though the supported compressed will be different

		/*	from ES2 spec
		  format
		    Specifies the format of the pixel data.
		    The following symbolic values are accepted:
		    GL_ALPHA,
		    GL_RGB,
		    GL_RGBA,
		    GL_LUMINANCE, and
		    GL_LUMINANCE_ALPHA.
		type
		    Specifies the data type of the pixel data.
		    The following symbolic values are accepted:
		    GL_UNSIGNED_BYTE,
		    GL_UNSIGNED_SHORT_5_6_5,
		    GL_UNSIGNED_SHORT_4_4_4_4, and
		    GL_UNSIGNED_SHORT_5_5_5_1.*/

		int internalFormat = 0;

		switch (textureFormat)
		{
		case Texture.INTENSITY:
			new Throwable("Texture.INTENSITY not supported").printStackTrace();
			// internalFormat = GL2.GL_INTENSITY;
			break;
		case Texture.LUMINANCE:
			internalFormat = GL2ES2.GL_LUMINANCE;
			break;
		case Texture.ALPHA:
			internalFormat = GL2ES2.GL_ALPHA;
			break;
		case Texture.LUMINANCE_ALPHA:
			internalFormat = GL2ES2.GL_LUMINANCE_ALPHA;
			break;
		case Texture.RGB:
			internalFormat = GL2ES2.GL_RGB;
			break;
		case Texture.RGBA:
			internalFormat = GL2ES2.GL_RGBA;
			break;
		default:
			assert false;
		}

		boolean createMipMaps = useAutoMipMap;

		int format = 0;

		if ((dataType == ImageComponentRetained.IMAGE_DATA_TYPE_BYTE_ARRAY)
				|| (dataType == ImageComponentRetained.IMAGE_DATA_TYPE_BYTE_BUFFER))
		{

			switch (imageFormat)
			{
			case ImageComponentRetained.TYPE_BYTE_BGR:
				format = GL2ES2.GL_BGR;
				break;
			case ImageComponentRetained.TYPE_BYTE_RGB:
				format = GL2ES2.GL_RGB;
				break;
			case ImageComponentRetained.TYPE_BYTE_ABGR:
				if (isExtensionAvailable.GL_EXT_abgr(gl))
				{
					// If its zero, should never come here!
					format = GL2.GL_ABGR_EXT;
				}
				else
				{
					assert false;
					return;
				}
				break;
			case ImageComponentRetained.TYPE_BYTE_RGBA:
				// all RGB types are stored as RGBA
				format = GL2ES2.GL_RGBA;
				break;
			case ImageComponentRetained.TYPE_BYTE_LA:
				// all LA types are stored as LA8
				format = GL2ES2.GL_LUMINANCE_ALPHA;
				break;
			case ImageComponentRetained.TYPE_BYTE_GRAY:
				if (internalFormat == GL2ES2.GL_ALPHA)
				{
					format = GL2ES2.GL_ALPHA;
				}
				else
				{
					format = GL2ES2.GL_LUMINANCE;
				}
				break;
			/////////////////////////////////////////////////// PJPJPJ////////////////////
			// DXT uncompressed D3DFMT_A8R8G8B8 indicator
			case GL2.GL_RGBA_S3TC:
				internalFormat = GL2ES2.GL_RGBA;
				format = GL2ES2.GL_RGBA;
				break;
			// notice fall through
			// DXT
			case GL2ES2.GL_COMPRESSED_RGBA_S3TC_DXT1_EXT:
			case GL2ES2.GL_COMPRESSED_RGBA_S3TC_DXT3_EXT:
			case GL2ES2.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT:
			case GL2.GL_COMPRESSED_LUMINANCE_ALPHA_LATC2_EXT:
				// ETC2
				// https://www.khronos.org/opengles/sdk/docs/man3/html/glCompressedTexImage2D.xhtml
			case GL3.GL_COMPRESSED_RGBA8_ETC2_EAC:
			case GL3.GL_COMPRESSED_RGB8_ETC2:
			case GL3.GL_COMPRESSED_RGB8_PUNCHTHROUGH_ALPHA1_ETC2:
			case GL3.GL_COMPRESSED_SRGB8_ALPHA8_ETC2_EAC:
			case GL3.GL_COMPRESSED_SRGB8_ETC2:
			case GL3.GL_COMPRESSED_SRGB8_PUNCHTHROUGH_ALPHA1_ETC2:
				// ASTC
			case GL3.GL_COMPRESSED_RGBA_ASTC_4x4_KHR:
			case GL3.GL_COMPRESSED_RGBA_ASTC_5x4_KHR:
			case GL3.GL_COMPRESSED_RGBA_ASTC_5x5_KHR:
			case GL3.GL_COMPRESSED_RGBA_ASTC_6x5_KHR:
			case GL3.GL_COMPRESSED_RGBA_ASTC_6x6_KHR:
			case GL3.GL_COMPRESSED_RGBA_ASTC_8x5_KHR:
			case GL3.GL_COMPRESSED_RGBA_ASTC_8x6_KHR:
			case GL3.GL_COMPRESSED_RGBA_ASTC_8x8_KHR:
			case GL3.GL_COMPRESSED_RGBA_ASTC_10x5_KHR:
			case GL3.GL_COMPRESSED_RGBA_ASTC_10x6_KHR:
			case GL3.GL_COMPRESSED_RGBA_ASTC_10x8_KHR:
			case GL3.GL_COMPRESSED_RGBA_ASTC_10x10_KHR:
			case GL3.GL_COMPRESSED_RGBA_ASTC_12x10_KHR:
			case GL3.GL_COMPRESSED_RGBA_ASTC_12x12_KHR:
				internalFormat = imageFormat;
				format = -1;// indicate compressed
				break;
			case ImageComponentRetained.TYPE_USHORT_GRAY:
			case ImageComponentRetained.TYPE_INT_BGR:
			case ImageComponentRetained.TYPE_INT_RGB:
			case ImageComponentRetained.TYPE_INT_ARGB:
			default:
				assert false;
				return;
			}

			if (dataType == ImageComponentRetained.IMAGE_DATA_TYPE_BYTE_ARRAY)
			{
				gl.glTexImage2D(target, level, internalFormat, width, height, boundaryWidth, format, GL2ES2.GL_UNSIGNED_BYTE,
						ByteBuffer.wrap((byte[]) data));
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}
			else
			{
				if (format == -1)
				{
					
					ByteBuffer bb = (ByteBuffer) data;

					gl.glCompressedTexImage2D(target, level, internalFormat, width, height, boundaryWidth, bb.limit(), bb);

					if (DO_OUTPUT_ERRORS)
					{
						int err = gl.glGetError();
						if (err != GL2ES2.GL_NO_ERROR)
						{
							System.out.println("glCompressedTexImage2D Error " + err + " target " + target + " level " + level
									+ " internalFormat " + internalFormat);
							System.out.println("width " + width + " height " + height + " boundaryWidth " + boundaryWidth + " bb.limit() "
									+ bb.limit());
							// https://www.khronos.org/opengles/sdk/docs/man3/html/glCompressedTexImage2D.xhtml
						}
					}
				}
				else
				{
					gl.glTexImage2D(target, level, internalFormat, width, height, boundaryWidth, format, GL2ES2.GL_UNSIGNED_BYTE,
							(Buffer) data);
					if (DO_OUTPUT_ERRORS)
					{
						int err = gl.glGetError();
						if (err != GL2ES2.GL_NO_ERROR)
						{
							System.out.println("glTexImage2D Error " + err + " target " + target + " level " + level + " internalFormat "
									+ internalFormat);
							System.out.println("width " + width + " height " + height + " boundaryWidth " + boundaryWidth + " format "
									+ format + " bb.limit() " + ((Buffer) data).limit());
							// https://www.khronos.org/opengles/sdk/docs/man3/html/glCompressedTexImage2D.xhtml
						}
					}
				}
			}
		}
		else if ((dataType == ImageComponentRetained.IMAGE_DATA_TYPE_INT_ARRAY)
				|| (dataType == ImageComponentRetained.IMAGE_DATA_TYPE_INT_BUFFER))
		{
			switch (imageFormat)
			{
			case ImageComponentRetained.TYPE_INT_BGR:  
				//PJ does this work correctly?
				format = GL2ES2.GL_RGB;
				break;
			case ImageComponentRetained.TYPE_INT_RGB:  
				format = GL2ES2.GL_RGB;
				break;
			case ImageComponentRetained.TYPE_INT_ARGB:
				format = GL2ES2.GL_RGBA;
				break;
			// This method only supports 3 and 4 components formats and INT types.			
			case ImageComponentRetained.TYPE_BYTE_LA:
			case ImageComponentRetained.TYPE_BYTE_GRAY:
			case ImageComponentRetained.TYPE_USHORT_GRAY:
			case ImageComponentRetained.TYPE_BYTE_BGR:
			case ImageComponentRetained.TYPE_BYTE_RGB:
			case ImageComponentRetained.TYPE_BYTE_RGBA:
			case ImageComponentRetained.TYPE_BYTE_ABGR:
			default:
				assert false;
				return;
			}

			if (dataType == ImageComponentRetained.IMAGE_DATA_TYPE_INT_ARRAY)
			{
				gl.glTexImage2D(target, level, internalFormat, width, height, boundaryWidth, format, GL2ES2.GL_UNSIGNED_BYTE,
						IntBuffer.wrap((int[]) data));
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}
			else
			{
				gl.glTexImage2D(target, level, internalFormat, width, height, boundaryWidth, format, GL2ES2.GL_UNSIGNED_BYTE,
						(Buffer) data);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}
		}
		else
		{
			assert false;
		}

		if(createMipMaps && level == 0)
		{
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_MIN_FILTER, GL2ES2.GL_LINEAR_MIPMAP_LINEAR);
			gl.glHint(GL.GL_GENERATE_MIPMAP_HINT, GL.GL_NICEST);
			gl.glGenerateMipmap(target);
			// as a new feature that happens seldom, output errors
			outputErrors(ctx);
		}

		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
	}

	private void updateTexture2DSubImage(Context ctx, int target, int level, int xoffset, int yoffset, int textureFormat, int imageFormat,
			int imgXOffset, int imgYOffset, int tilew, int width, int height, int dataType, Object data)
	{
		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		if (imgXOffset > 0 || (width < tilew))
		{
			// PJ not sure what should be happening here
			// new Throwable("(imgXOffset > 0 || (width < tilew))").printStackTrace();
		}

		int internalFormat = 0;

		switch (textureFormat)
		{
		case Texture.INTENSITY:
			// internalFormat = GL2.GL_INTENSITY;
			new Throwable("Texture.INTENSITY not supported").printStackTrace();
			break;
		case Texture.LUMINANCE:
			internalFormat = GL2ES2.GL_LUMINANCE;
			break;
		case Texture.ALPHA:
			internalFormat = GL2ES2.GL_ALPHA;
			break;
		case Texture.LUMINANCE_ALPHA:
			internalFormat = GL2ES2.GL_LUMINANCE_ALPHA;
			break;
		case Texture.RGB:
			internalFormat = GL2ES2.GL_RGB;
			break;
		case Texture.RGBA:
			internalFormat = GL2ES2.GL_RGBA;
			break;
		default:
			assert false;
		}

		if ((dataType == ImageComponentRetained.IMAGE_DATA_TYPE_BYTE_ARRAY)
				|| (dataType == ImageComponentRetained.IMAGE_DATA_TYPE_BYTE_BUFFER))
		{
			int format = 0;
			int numBytes = 0;

			switch (imageFormat)
			{
			case ImageComponentRetained.TYPE_BYTE_BGR:
				format = GL2ES2.GL_BGR;
				numBytes = 3;
				break;
			case ImageComponentRetained.TYPE_BYTE_RGB:
				format = GL2ES2.GL_RGB;
				numBytes = 3;
				break;
			case ImageComponentRetained.TYPE_BYTE_ABGR:
				if (isExtensionAvailable.GL_EXT_abgr(gl))
				{
					// If its zero, should never come here!
					format = GL2.GL_ABGR_EXT;
					numBytes = 4;
				}
				else
				{
					assert false;
					return;
				}
				break;
			case ImageComponentRetained.TYPE_BYTE_RGBA:
				// all RGB types are stored as RGBA
				format = GL2ES2.GL_RGBA;
				numBytes = 4;
				break;
			case ImageComponentRetained.TYPE_BYTE_LA:
				// all LA types are stored as LA8
				format = GL2ES2.GL_LUMINANCE_ALPHA;
				numBytes = 2;
				break;
			case ImageComponentRetained.TYPE_BYTE_GRAY:
				if (internalFormat == GL2ES2.GL_ALPHA)
				{
					format = GL2ES2.GL_ALPHA;
					numBytes = 1;
				}
				else
				{
					format = GL2ES2.GL_LUMINANCE;
					numBytes = 1;
				}
				break;
			case ImageComponentRetained.TYPE_USHORT_GRAY:
			case ImageComponentRetained.TYPE_INT_BGR:
			case ImageComponentRetained.TYPE_INT_RGB:
			case ImageComponentRetained.TYPE_INT_ARGB:
			default:
				assert false;
				return;
			}

			ByteBuffer buf = null;
			if (dataType == ImageComponentRetained.IMAGE_DATA_TYPE_BYTE_ARRAY)
			{
				buf = ByteBuffer.wrap((byte[]) data);
			}
			else
			{
				buf = (ByteBuffer) data;
			}

			// offset by the imageOffset
			buf.position((tilew * imgYOffset + imgXOffset) * numBytes);
			gl.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, GL2ES2.GL_UNSIGNED_BYTE, buf);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}
		else if ((dataType == ImageComponentRetained.IMAGE_DATA_TYPE_INT_ARRAY)
				|| (dataType == ImageComponentRetained.IMAGE_DATA_TYPE_INT_BUFFER))
		{
			int format = 0;
			switch (imageFormat)
			{
			case ImageComponentRetained.TYPE_INT_BGR:  
				//PJ does this work correctly?
				format = GL2ES2.GL_RGB;
				break;
			case ImageComponentRetained.TYPE_INT_RGB:  
				format = GL2ES2.GL_RGB;
				break;
			case ImageComponentRetained.TYPE_INT_ARGB:
				format = GL2ES2.GL_RGBA;
				break;
			// This method only supports 3 and 4 components formats and INT types.
			case ImageComponentRetained.TYPE_BYTE_LA:
			case ImageComponentRetained.TYPE_BYTE_GRAY:
			case ImageComponentRetained.TYPE_USHORT_GRAY:
			case ImageComponentRetained.TYPE_BYTE_BGR:
			case ImageComponentRetained.TYPE_BYTE_RGB:
			case ImageComponentRetained.TYPE_BYTE_RGBA:
			case ImageComponentRetained.TYPE_BYTE_ABGR:
			default:
				assert false;
				return;
			}

			IntBuffer buf = null;
			if (dataType == ImageComponentRetained.IMAGE_DATA_TYPE_INT_ARRAY)
			{
				buf = IntBuffer.wrap((int[]) data);
			}
			else
			{
				buf = (IntBuffer) data;
			}

			// offset by the imageOffset
			buf.position(tilew * imgYOffset + imgXOffset);
			gl.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, GL2ES2.GL_UNSIGNED_BYTE, buf);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}
		else
		{
			assert false;
			return;
		}

		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

	}

	private static void updateTextureFilterModes(Context ctx, int target, int minFilter, int magFilter)
	{
		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		// FIXME: unclear whether we really need to set up the enum values
		// in the JoglContext as is done in the native code depending on
		// extension availability; maybe this is the defined fallback
		// behavior of the various Java3D modes

		// set texture min filter
		switch (minFilter)
		{
		case Texture.FASTEST:
		case Texture.BASE_LEVEL_POINT:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_MIN_FILTER, GL2ES2.GL_NEAREST);
			break;
		case Texture.BASE_LEVEL_LINEAR:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_MIN_FILTER, GL2ES2.GL_LINEAR);
			break;
		case Texture.MULTI_LEVEL_POINT:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_MIN_FILTER, GL2ES2.GL_NEAREST_MIPMAP_NEAREST);
			break;
		case Texture.NICEST:
		case Texture.MULTI_LEVEL_LINEAR:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_MIN_FILTER, GL2ES2.GL_LINEAR_MIPMAP_LINEAR);
			break;
		case Texture.FILTER4:
			// We should never get here as we've disabled the FILTER4 feature
			// gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_MIN_FILTER,
			// GL2ES2.GL_FILTER4_SGIS);
			break;
		}
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		// set texture mag filter
		switch (magFilter)
		{
		case Texture.FASTEST:
		case Texture.BASE_LEVEL_POINT:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_MAG_FILTER, GL2ES2.GL_NEAREST);
			break;
		case Texture.NICEST:
		case Texture.BASE_LEVEL_LINEAR:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_MAG_FILTER, GL2ES2.GL_LINEAR);
			break;
		case Texture.LINEAR_SHARPEN:
		case Texture.LINEAR_SHARPEN_RGB:
		case Texture.LINEAR_SHARPEN_ALPHA:
		case Texture2D.LINEAR_DETAIL:
		case Texture2D.LINEAR_DETAIL_RGB:
		case Texture2D.LINEAR_DETAIL_ALPHA:
		case Texture.FILTER4:
		default:
			assert false;
			return;
		}

		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
	}

	void updateTextureBoundary(Context ctx, int target, int boundaryModeS, int boundaryModeT, int boundaryModeR, float boundaryRed,
			float boundaryGreen, float boundaryBlue, float boundaryAlpha)
	{
		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		// except the R gear at bottom and boundary color
		// but I'm dropping 3dtexture support so no probs and who cares about boundary color
		// CLAMP_TO_BOUNDARYand GL_CLAMP are gone so now just set as GL_CLAMP_TO_EDGE
		// FIXME: GL_MIRRORED_REPEAT needs to be added

		// set texture wrap parameter
		switch (boundaryModeS)
		{
		case Texture.WRAP:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_WRAP_S, GL2ES2.GL_REPEAT);
			break;
		case Texture.CLAMP:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_WRAP_S, GL2ES2.GL_CLAMP_TO_EDGE);
			break;
		case Texture.CLAMP_TO_EDGE:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_WRAP_S, GL2ES2.GL_CLAMP_TO_EDGE);
			break;
		case Texture.CLAMP_TO_BOUNDARY:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_WRAP_S, GL2ES2.GL_CLAMP_TO_EDGE);
			break;
		}
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		switch (boundaryModeT)
		{
		case Texture.WRAP:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_WRAP_T, GL2ES2.GL_REPEAT);
			break;
		case Texture.CLAMP:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_WRAP_T, GL2ES2.GL_CLAMP_TO_EDGE);
			break;
		case Texture.CLAMP_TO_EDGE:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_WRAP_T, GL2ES2.GL_CLAMP_TO_EDGE);
			break;
		case Texture.CLAMP_TO_BOUNDARY:
			gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_WRAP_T, GL2ES2.GL_CLAMP_TO_EDGE);
			break;
		}
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		// applies to Texture3D only
		/*		if (boundaryModeR != -1)
				{
					switch (boundaryModeR)
					{
					case Texture.WRAP:
						gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_WRAP_R, GL2ES2.GL_REPEAT);
						break;
		
					case Texture.CLAMP:
						gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_WRAP_R, GL2ES2.GL_CLAMP);
						break;
					case Texture.CLAMP_TO_EDGE:
						gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_WRAP_R, GL2ES2.GL_CLAMP_TO_EDGE);
						break;
					case Texture.CLAMP_TO_BOUNDARY:
						gl.glTexParameteri(target, GL2ES2.GL_TEXTURE_WRAP_R, GL2ES2.GL_CLAMP_TO_BORDER);
						break;
					}
				}*/

		//http://stackoverflow.com/questions/913801/glteximage2d
		// texture border no longer supported on ES2

		/*if (boundaryModeS == Texture.CLAMP || boundaryModeT == Texture.CLAMP || boundaryModeR == Texture.CLAMP)
		{
			// set texture border color
			float[] color = new float[4];
			color[0] = boundaryRed;
			color[1] = boundaryGreen;
			color[2] = boundaryBlue;
			color[3] = boundaryAlpha;
			gl.glTexParameterfv(target, GL2ES2.GL_TEXTURE_BORDER_COLOR, color, 0);
		}*/

	}

	// mapping from java enum to gl enum
	private static final int[] _gl_textureCubeMapFace = { GL2ES2.GL_TEXTURE_CUBE_MAP_POSITIVE_X, GL2ES2.GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
			GL2ES2.GL_TEXTURE_CUBE_MAP_POSITIVE_Y, GL2ES2.GL_TEXTURE_CUBE_MAP_NEGATIVE_Y, GL2ES2.GL_TEXTURE_CUBE_MAP_POSITIVE_Z,
			GL2ES2.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z, };

	// The following three methods are used in multi-pass case

	// native method for setting blend color
	@Override
	void setBlendColor(Context ctx, float red, float green, float blue, float alpha)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setBlendColor()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.setBlendColor++;

		GL2ES2 gl = null;// get late expensive

		if (isExtensionAvailable.GL_ARB_imaging(gl))
		{
			gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
			gl.glBlendColor(red, green, blue, alpha);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}

	}

	// native method for setting blend func
	@Override
	void setBlendFunc(Context ctx, int srcBlendFunction, int dstBlendFunction)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setBlendFunc()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.setBlendFunc++;

		GL2ES2 gl = null;// get late expensive
		Jogl2es2Context joglesctx = ((Jogl2es2Context) ctx);

		if (!MINIMISE_NATIVE_CALLS_TRANSPARENCY || (joglesctx.gl_state.glEnableGL_BLEND != true
				|| joglesctx.gl_state.srcBlendFunction != srcBlendFunction || joglesctx.gl_state.dstBlendFunction != dstBlendFunction))
		{
			gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
			gl.glEnable(GL2ES2.GL_BLEND);
			gl.glBlendFunc(blendFunctionTable[srcBlendFunction], blendFunctionTable[dstBlendFunction]);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
			if (MINIMISE_NATIVE_CALLS_TRANSPARENCY)
			{
				joglesctx.gl_state.glEnableGL_BLEND = true;
				joglesctx.gl_state.srcBlendFunction = srcBlendFunction;
				joglesctx.gl_state.dstBlendFunction = dstBlendFunction;
			}
		}
	}

	// native method for setting light enables
	@Override
	void setLightEnables(Context ctx, long enableMask, int maxLights)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setLightEnables()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.setLightEnables++;

		Jogl2es2Context joglesctx = (Jogl2es2Context) ctx;
		joglesctx.maxLights = maxLights;
		joglesctx.numberOfLights = 0;
		for (int i = 0; i < maxLights; i++)
		{
			boolean enable = (enableMask & (1 << i)) >> i != 0;
			if (joglesctx.glLightSource[i] == null)
			{
				joglesctx.glLightSource[i] = new glLightSource();
			}

			joglesctx.glLightSource[i].enabled = enable ? 1 : 0;
			joglesctx.numberOfLights += enable ? 1 : 0;

			// clear gl_state
			joglesctx.gl_state.glLightSource[i] = null; // this is a pointer not a container
		}
	}

	// native method for setting scene ambient
	@Override
	void setSceneAmbient(Context ctx, float red, float green, float blue)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setSceneAmbient()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.setSceneAmbient++;
		Jogl2es2Context joglesctx = (Jogl2es2Context) ctx;

		// do we need to push onto the shader now?
		if (joglesctx.currentAmbientColor.x != red || joglesctx.currentAmbientColor.y != green || joglesctx.currentAmbientColor.z != blue)
		{	joglesctx.gl_state.glLightModelambient.x = Float.NEGATIVE_INFINITY;

		joglesctx.currentAmbientColor.x = red;
		joglesctx.currentAmbientColor.y = green;
		joglesctx.currentAmbientColor.z = blue;
		joglesctx.currentAmbientColor.w = 1.0f;}

	}

	// native method for disabling modelClip
	// this is called as a reset
	@Override
	void disableModelClip(Context ctx)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.disableModelClip()");
	}

	// native method for activating a particular texture unit
	@Override
	void activeTextureUnit(Context ctx, int texUnitIndex)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.activeTextureUnit(texUnitIndex= " + texUnitIndex + ")");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.activeTextureUnit++;

		Jogl2es2Context joglesContext = (Jogl2es2Context) ctx;
		GL2ES2 gl = null;// get late expensive

		if (texUnitIndex >= 0)
		{
			if (!MINIMISE_NATIVE_CALLS_TEXTURE || (joglesContext.gl_state.glActiveTexture != (texUnitIndex + GL2ES2.GL_TEXTURE0)))
			{
				gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
				gl.glActiveTexture(texUnitIndex + GL2ES2.GL_TEXTURE0);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_TEXTURE)
					joglesContext.gl_state.glActiveTexture = (texUnitIndex + GL2ES2.GL_TEXTURE0);
			}
		}
	}

	// native method for setting default texture
	@Override
	void resetTextureNative(Context ctx, int texUnitIndex)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.resetTextureNative(texUnitIndex=" + texUnitIndex + ")");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.resetTextureNative++;

		Jogl2es2Context joglesContext = (Jogl2es2Context) ctx;
		GL2ES2 gl = null;// get late expensive

		if (texUnitIndex >= 0)
		{
			if (!MINIMISE_NATIVE_CALLS_TEXTURE || (joglesContext.gl_state.glActiveTexture != (texUnitIndex + GL2ES2.GL_TEXTURE0)))
			{
				gl = gl == null ? ((Jogl2es2Context) ctx).gl2es2() : gl;
				gl.glActiveTexture(texUnitIndex + GL2ES2.GL_TEXTURE0);
				// TODO: should I bind these to 0?
				// gl.glBindTexture(GL2ES2.GL_TEXTURE_2D, 0);//-1 is no texture , 0 is default
				// gl.glBindTexture(GL2ES2.GL_TEXTURE_CUBE_MAP, 0);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
				if (MINIMISE_NATIVE_CALLS_TEXTURE)
					joglesContext.gl_state.glActiveTexture = (texUnitIndex + GL2ES2.GL_TEXTURE0);
			}
		}
	}

	// The native method for setting the ModelView matrix.
	@Override
	void setModelViewMatrix(Context ctx, double[] viewMatrix, double[] modelMatrix)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setModelViewMatrix(viewMatrix= " + lineString(viewMatrix) + " modelMatrix= "
					+ lineString(modelMatrix) + ")");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.setModelViewMatrix++;

		Jogl2es2Context joglesctx = (Jogl2es2Context) ctx;

		//joglesctx.matrixUtil.deburnV.set(viewMatrix);
		//joglesctx.matrixUtil.deburnV.transpose();// now done in ffp by call to native
		joglesctx.currentViewMat.set(viewMatrix);

		//joglesctx.matrixUtil.deburnM.set(modelMatrix);
		//joglesctx.matrixUtil.deburnM.transpose();// now done in ffp by call to native
		joglesctx.currentModelMat.set(modelMatrix);

		joglesctx.gl_state.modelMatrix.m00 = Double.NEGATIVE_INFINITY;// indicate needs pushing onto shader	
		joglesctx.gl_state.glModelViewMatrix.m00 = Double.NEGATIVE_INFINITY;
		joglesctx.gl_state.glModelViewMatrixInverse.m00 = Double.NEGATIVE_INFINITY;
		joglesctx.gl_state.glModelViewProjectionMatrix.m00 = Double.NEGATIVE_INFINITY;
		joglesctx.gl_state.glNormalMatrix.m00 = Double.NEGATIVE_INFINITY;

		//Moved up into setffp and only calc'ed if requested
		joglesctx.currentModelViewMat.m00 = Double.NEGATIVE_INFINITY;// indicate no longer valid	
		joglesctx.currentModelViewMatInverse.m00 = Double.NEGATIVE_INFINITY;
		joglesctx.currentModelViewProjMat.m00 = Double.NEGATIVE_INFINITY;
		joglesctx.currentNormalMat.m00 = Double.NEGATIVE_INFINITY;

		/*
		
				joglesctx.currentModelViewMat.mul(joglesctx.matrixUtil.deburnV, joglesctx.matrixUtil.deburnM);
		
				
				joglesctx.currentModelViewProjMat.mul(joglesctx.currentProjMat, joglesctx.currentModelViewMat);
		
				// use only the upper left as it is a 3x3 rotation matrix
				JoglesMatrixUtil.transposeInvert(joglesctx.currentModelViewMat, joglesctx.currentNormalMat);				
			*/

		// note this MV MUST be used by the lights when setting position!
		//https://www.opengl.org/discussion_boards/showthread.php/168706-Light-Position-in-eye-s-cordinate
	}

	// The native method for setting the Projection matrix.
	@Override
	void setProjectionMatrix(Context ctx, double[] projMatrix)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setProjectionMatrix()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.setProjectionMatrix++;

		Jogl2es2Context joglesctx = (Jogl2es2Context) ctx;

		// Invert the Z value in clipping coordinates because OpenGL uses
		// left-handed clipping coordinates, while Java3D defines right-handed
		// coordinates everywhere.
		projMatrix[8] *= -1.0;
		projMatrix[9] *= -1.0;
		projMatrix[10] *= -1.0;
		projMatrix[11] *= -1.0;

		joglesctx.currentProjMat.set(projMatrix);
		// joglesctx.currentProjMat.transpose(); // done in set ffp now

		// reverse it back in case others use it
		projMatrix[8] *= -1.0;
		projMatrix[9] *= -1.0;
		projMatrix[10] *= -1.0;
		projMatrix[11] *= -1.0;

	}

	// The native method for setting the Viewport.
	@Override
	void setViewport(Context ctx, int x, int y, int width, int height)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setViewport()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.setViewport++;
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.setViewportTime = System.nanoTime();

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		gl.glViewport(x, y, width, height);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
	}

	private static String lineString(double[] da)
	{
		String ret = "double[";
		for (double d : da)
			ret += " " + d;
		return ret + "]";
	}

	private static String lineString(float[] fa)
	{
		String ret = "float[";
		for (float f : fa)
			ret += " " + f;
		return ret + "]";
	}

	@Override
	void freeTexture(Context ctx, int id)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.freeTexture()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.freeTexture++;

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		if (id > 0)
		{
			int[] tmp = new int[1];
			tmp[0] = id;
			gl.glDeleteTextures(1, tmp, 0);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}
		else
		{
			System.err.println("tried to delete tex with texid <= 0");
		}
	}

	@Override
	int generateTexID(Context ctx)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.generateTexID()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.generateTexID++;

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		int[] tmp = new int[] { -1 };
		gl.glGenTextures(1, tmp, 0);

		if (tmp[0] < 1)
			return -1;

		return tmp[0];
	}

	@Override
	void texturemapping(Context ctx, int px, int py, int minX, int minY, int maxX, int maxY, int texWidth, int texHeight, int rasWidth,
			int format, int objectId, byte[] imageYdown, int winWidth, int winHeight)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.texturemapping()");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		int glType = GL.GL_RGBA;

		disableAttribFor2D(gl);

		gl.glDepthMask(false);
		gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
		gl.glBindTexture(GL.GL_TEXTURE_2D, objectId);
		// set up texture parameter
		gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
		gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
		gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT);
		gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT);

		gl.glEnable(GL.GL_BLEND);
		gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
		
		if (isExtensionAvailable.GL_EXT_abgr(gl))
		{
			glType = GL2.GL_ABGR_EXT;
		}
		else
		{
			switch (format)
			{
			case ImageComponentRetained.TYPE_BYTE_RGBA:
				glType = GL.GL_RGBA;
				break;
			case ImageComponentRetained.TYPE_BYTE_RGB:
				glType = GL.GL_RGB;
				break;
			}
		}

		gl.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, minX, minY, maxX - minX, maxY - minY, glType, GL.GL_UNSIGNED_BYTE,
				ByteBuffer.wrap(imageYdown));

		float texMinU = (float) minX / (float) texWidth;
		float texMinV = (float) minY / (float) texHeight;
		float texMaxU = (float) maxX / (float) texWidth;
		float texMaxV = (float) maxY / (float) texHeight;
		float halfWidth = winWidth / 2.0f;
		float halfHeight = winHeight / 2.0f;

		float mapMinX = ((px + minX) - halfWidth) / halfWidth;
		float mapMinY = (halfHeight - (py + maxY)) / halfHeight;
		float mapMaxX = (px + maxX - halfWidth) / halfWidth;
		float mapMaxY = (halfHeight - (py + minY)) / halfHeight;

		float mapZ = 0;

		//NOTE!!!!!! very very well, tex*V is swapped max/min in order to upright the Y of the image!!
		renderTexturedQuad(ctx, texMinU, texMaxU, texMaxV, texMinV, mapMinX, mapMaxX, mapMinY, mapMaxY, mapZ);

		// Java 3D always clears the Z-buffer
		gl.glDepthMask(true);
		gl.glClear(GL.GL_DEPTH_BUFFER_BIT);
	}

	@Override
	boolean initTexturemapping(Context ctx, int texWidth, int texHeight, int objectId)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.initTexturemapping()");

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		gl.glBindTexture(GL.GL_TEXTURE_2D, objectId);

		int glType = GL.GL_RGBA;
		if (isExtensionAvailable.GL_EXT_abgr(gl))
		{
			glType = GL2.GL_ABGR_EXT;
		}
		else
		{
			glType = GL.GL_RGBA;
		}
		//some sort of memory space check below?
		/*gl.glTexImage2D(GL2.GL_PROXY_TEXTURE_2D, 0, GL2ES2.GL_RGBA, texWidth, texHeight, 0, glType, GL.GL_UNSIGNED_BYTE, null);
		
		int[] width = new int[1];
		gl.glGetTexLevelParameteriv(GL2.GL_PROXY_TEXTURE_2D, 0, GL2.GL_TEXTURE_WIDTH, width, 0);
		
		if (width[0] <= 0)
		{
			return false;
		}*/

		// init texture size only without filling the pixels
		gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, texWidth, texHeight, 0, glType, GL.GL_UNSIGNED_BYTE, null);

		return true;
	}

	// Set glDepthMask.
	@Override
	void setDepthBufferWriteEnable(Context ctx, boolean mode)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setDepthBufferWriteEnable()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.setDepthBufferWriteEnable++;

		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();

		if (mode)
		{
			gl.glDepthMask(true);
		}
		else
		{
			gl.glDepthMask(false);
		}
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
	}

	// Set internal render mode to one of FIELD_ALL, FIELD_LEFT or
	// FIELD_RIGHT. Note that it is up to the caller to ensure that
	// stereo is available before setting the mode to FIELD_LEFT or
	// FIELD_RIGHT. The boolean doubleBuffer is TRUE for double buffered mode, FALSE
	// for single buffering.
	@Override
	void setRenderMode(Context ctx, int mode, boolean doubleBuffer)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setRenderMode()");

		// TODO: why is this not done in swap or sync?  
		// UGLY HACK the render mode is set to Canvas3D.FIELD_ALL after all
		// geoms are drawn
		// so I take the opportunity to unbind the vertex array

		GL2ES3 gl2es3 = ((Jogl2es2Context) ctx).gl2es3();
		if (gl2es3 != null)
		{
			gl2es3.glBindVertexArray(0);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}

		// GL2ES2 gl = context(ctx).getGL().getGL2ES2();
		// no no drawBuffer, possibly just skip it for now
		// ES2 is much more complex with buffers https://www.khronos.org/registry/gles/extensions/EXT/EXT_draw_buffers.txt

		/*	int drawBuf = 0;
			if (doubleBuffer)
			{
				drawBuf = GL2ES2.GL_BACK;
				switch (mode)
				{
				case Canvas3D.FIELD_LEFT:
					drawBuf = GL2ES2.GL_BACK_LEFT;
					break;
				case Canvas3D.FIELD_RIGHT:
					drawBuf = GL2ES2.GL_BACK_RIGHT;
					break;
				case Canvas3D.FIELD_ALL:
					drawBuf = GL2ES2.GL_BACK;
					break;
				}
			}
			else
			{
				drawBuf = GL2ES2.GL_FRONT;
				switch (mode)
				{
				case Canvas3D.FIELD_LEFT:
					drawBuf = GL2ES2.GL_FRONT_LEFT;
					break;
				case Canvas3D.FIELD_RIGHT:
					drawBuf = GL2ES2.GL_FRONT_RIGHT;
					break;
				case Canvas3D.FIELD_ALL:
					drawBuf = GL2ES2.GL_FRONT;
					break;
				}
			}
		
			gl.glDrawBuffer(drawBuf);*/
	}

	private static int getFunctionValue(int func)
	{
		switch (func)
		{
		case RenderingAttributes.ALWAYS:
			func = GL2ES2.GL_ALWAYS;
			break;
		case RenderingAttributes.NEVER:
			func = GL2ES2.GL_NEVER;
			break;
		case RenderingAttributes.EQUAL:
			func = GL2ES2.GL_EQUAL;
			break;
		case RenderingAttributes.NOT_EQUAL:
			func = GL2ES2.GL_NOTEQUAL;
			break;
		case RenderingAttributes.LESS:
			func = GL2ES2.GL_LESS;
			break;
		case RenderingAttributes.LESS_OR_EQUAL:
			func = GL2ES2.GL_LEQUAL;
			break;
		case RenderingAttributes.GREATER:
			func = GL2ES2.GL_GREATER;
			break;
		case RenderingAttributes.GREATER_OR_EQUAL:
			func = GL2ES2.GL_GEQUAL;
			break;
		}

		return func;
	}

	private static int getStencilOpValue(int op)
	{
		switch (op)
		{
		case RenderingAttributes.STENCIL_KEEP:
			op = GL2ES2.GL_KEEP;
			break;
		case RenderingAttributes.STENCIL_ZERO:
			op = GL2ES2.GL_ZERO;
			break;
		case RenderingAttributes.STENCIL_REPLACE:
			op = GL2ES2.GL_REPLACE;
			break;
		case RenderingAttributes.STENCIL_INCR:
			op = GL2ES2.GL_INCR;
			break;
		case RenderingAttributes.STENCIL_DECR:
			op = GL2ES2.GL_DECR;
			break;
		case RenderingAttributes.STENCIL_INVERT:
			op = GL2ES2.GL_INVERT;
			break;
		}

		return op;
	}

	// ---------------------------------------------------------------------

	//
	// TransparencyAttributesRetained methods
	//

	private static final int[] blendFunctionTable = new int[TransparencyAttributes.MAX_BLEND_FUNC_TABLE_SIZE];

	static
	{
		blendFunctionTable[TransparencyAttributes.BLEND_ZERO] = GL2ES2.GL_ZERO;
		blendFunctionTable[TransparencyAttributes.BLEND_ONE] = GL2ES2.GL_ONE;
		blendFunctionTable[TransparencyAttributes.BLEND_SRC_ALPHA] = GL2ES2.GL_SRC_ALPHA;
		blendFunctionTable[TransparencyAttributes.BLEND_ONE_MINUS_SRC_ALPHA] = GL2ES2.GL_ONE_MINUS_SRC_ALPHA;
		blendFunctionTable[TransparencyAttributes.BLEND_DST_COLOR] = GL2ES2.GL_DST_COLOR;
		blendFunctionTable[TransparencyAttributes.BLEND_ONE_MINUS_DST_COLOR] = GL2ES2.GL_ONE_MINUS_DST_COLOR;
		blendFunctionTable[TransparencyAttributes.BLEND_SRC_COLOR] = GL2ES2.GL_SRC_COLOR;
		blendFunctionTable[TransparencyAttributes.BLEND_ONE_MINUS_SRC_COLOR] = GL2ES2.GL_ONE_MINUS_SRC_COLOR;
		blendFunctionTable[TransparencyAttributes.BLEND_CONSTANT_COLOR] = GL2ES2.GL_CONSTANT_COLOR;
	}

	// ----------------------------------------------------------------------
	// Helper private functions for Canvas3D
	//
	// USED BY CONTEXT QUERIER BELOW which is used for create new context
	private static boolean getPropertiesFromCurrentContext(JoglContext ctx, GL2ES2 gl)
	{
		// FIXME: this is a heavily abridged set of the stuff in Canvas3D.c;
		// probably need to pull much more in
		int[] tmp = new int[1];
		gl.glGetIntegerv(GL2ES2.GL_MAX_TEXTURE_IMAGE_UNITS, tmp, 0);
		ctx.setMaxTexCoordSets(tmp[0]);
		if (VirtualUniverse.mc.transparentOffScreen)
		{
			ctx.setAlphaClearValue(0.0f);
		}
		else
		{
			ctx.setAlphaClearValue(1.0f);
		}
		return true;
	}

	// Used by createNewContext below
	private static int[] extractVersionInfo(String versionString)
	{
		// FIXME: use the second flash regex system to get the first number out
		// examples
		// OpenGL ES 3.0 V@136.0 AU@ (GIT@I3fa967cfef)
		// 4.5.0 NVIDIA 353.82
		// System.err.println("versionString: " + versionString);
		if (versionString.startsWith("OpenGL ES "))
			versionString = versionString.substring("OpenGL ES ".length());
		StringTokenizer tok = new StringTokenizer(versionString, ". ");
		int major = Integer.valueOf(tok.nextToken()).intValue();
		int minor = Integer.valueOf(tok.nextToken()).intValue();

		// See if there's vendor-specific information which might
		// imply a more recent OpenGL version
		tok = new StringTokenizer(versionString, " ");
		if (tok.hasMoreTokens())
		{
			tok.nextToken();
			if (tok.hasMoreTokens())
			{
				Pattern p = Pattern.compile("\\D*(\\d+)\\.(\\d+)\\.?(\\d*).*");
				Matcher m = p.matcher(tok.nextToken());
				if (m.matches())
				{
					int altMajor = Integer.valueOf(m.group(1)).intValue();
					int altMinor = Integer.valueOf(m.group(2)).intValue();
					// Avoid possibly confusing situations by requiring
					// major version to match
					if (altMajor == major && altMinor > minor)
					{
						minor = altMinor;
					}
				}
			}
		}
		return new int[] { major, minor };
	}

	// Used by createNewContext below
	private static void checkTextureExtensions(Canvas3D cv, JoglContext ctx, GL2ES2 gl, boolean gl13)
	{
		if (gl13)
		{

			// FIXME: setting this to cv.maxTexCoordSets = 8; and cutting the rest out doesn't work!
			cv.textureExtendedFeatures |= Canvas3D.TEXTURE_MULTI_TEXTURE;
			cv.multiTexAccelerated = true;
			int[] tmp = new int[1];
			gl.glGetIntegerv(GL2ES2.GL_MAX_TEXTURE_IMAGE_UNITS, tmp, 0);
			cv.maxTextureUnits = tmp[0];
			cv.maxTexCoordSets = cv.maxTextureUnits;
		}

		//Combine function gone as shader not FFP
		/*if (gl.isExtensionAvailable("GL_ARB_texture_env_combine"))
		{
			cv.textureExtendedFeatures |= Canvas3D.TEXTURE_COMBINE;
			cv.textureExtendedFeatures |= Canvas3D.TEXTURE_COMBINE_SUBTRACT;
		}
		else if (gl.isExtensionAvailable("GL_EXT_texture_env_combine"))
		{
			cv.textureExtendedFeatures |= Canvas3D.TEXTURE_COMBINE;
		}
		
		if (gl.isExtensionAvailable("GL_ARB_texture_env_dot3") || gl.isExtensionAvailable("GL_EXT_texture_env_dot3"))
		{
			cv.textureExtendedFeatures |= Canvas3D.TEXTURE_COMBINE_DOT3;
		}*/

		if (gl13)
		{
			cv.textureExtendedFeatures |= Canvas3D.TEXTURE_CUBE_MAP;
		}

		if (gl.isExtensionAvailable("GL_EXT_texture_filter_anisotropic"))
		{
			cv.textureExtendedFeatures |= Canvas3D.TEXTURE_ANISOTROPIC_FILTER;
			float[] tmp = new float[1];
			gl.glGetFloatv(GL2ES2.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, tmp, 0);
			cv.anisotropicDegreeMax = tmp[0];
		}

		//Gles uses the GL_OES_texture_npot extension 
		if (!VirtualUniverse.mc.enforcePowerOfTwo
				&& (gl.isExtensionAvailable("GL_ARB_texture_non_power_of_two") || gl.isExtensionAvailable("GL_OES_texture_npot")))
		{
			cv.textureExtendedFeatures |= Canvas3D.TEXTURE_NON_POWER_OF_TWO;
		}

		//autoMipMapGeneration
		//if (gl.isExtensionAvailable("GL_SGIS_generate_mipmap"))
		{
			cv.textureExtendedFeatures |= Canvas3D.TEXTURE_AUTO_MIPMAP_GENERATION;
		}

	}

	// Used by createNewContext above
	private static void checkGLSLShaderExtensions(Canvas3D cv, JoglContext ctx, GL2ES2 gl, boolean hasgl13)
	{

			//PJPJ Gl2ES2 MUST support shaders, and the support is NOT an extension
			// Force shaders to be disabled, since no multitexture support
			//	if (!hasgl13)
			//		return;

			//	if ((gl.isExtensionAvailable("GL_ARB_shader_objects") //
			//			&& gl.isExtensionAvailable("GL_ARB_shading_language_100")) //
			//			|| gl.isExtensionAvailable("GL_AMD_program_binary_Z400"))
		{

			// FIXME: this isn't complete and would need to set up the
			// JoglContext for dispatch of various routines such as those
			// related to vertex attributes
			int[] tmp = new int[1];
			gl.glGetIntegerv(GL2ES2.GL_MAX_TEXTURE_IMAGE_UNITS, tmp, 0);
			cv.maxTextureImageUnits = tmp[0];
			gl.glGetIntegerv(GL2ES2.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS, tmp, 0);
			cv.maxVertexTextureImageUnits = tmp[0];
			gl.glGetIntegerv(GL2ES2.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, tmp, 0);
			cv.maxCombinedTextureImageUnits = tmp[0];
			int vertexAttrOffset = VirtualUniverse.mc.glslVertexAttrOffset;
			ctx.setGLSLVertexAttrOffset(vertexAttrOffset);
			gl.glGetIntegerv(GL2ES2.GL_MAX_VERTEX_ATTRIBS, tmp, 0);
			cv.maxVertexAttrs = tmp[0];
			// decr count to allow for reserved vertex attrs
			cv.maxVertexAttrs -= vertexAttrOffset;
			if (cv.maxVertexAttrs < 0)
			{
				cv.maxVertexAttrs = 0;
			}
			
			//http://stackoverflow.com/questions/26682631/webgl-shaders-maximum-number-of-varying-variables
			gl.glGetIntegerv(GL2ES2.GL_MAX_VARYING_VECTORS, tmp, 0);
			cv.maxVaryingVectors = tmp[0];
			
			cv.shadingLanguageGLSL = true;
		}
	}

	// Used by createNewContext below
	private static void setupCanvasProperties(Canvas3D cv, JoglContext ctx, GL2ES2 gl)
	{
		// Note: this includes relevant portions from both the
		// NativePipeline's getPropertiesFromCurrentContext and setupCanvasProperties

		// Reset all fields
		cv.multiTexAccelerated = false;
		cv.maxTextureUnits = 1;
		cv.maxTexCoordSets = 1;
		cv.maxTextureImageUnits = 0;
		cv.maxVertexTextureImageUnits = 0;
		cv.maxCombinedTextureImageUnits = 0;
		cv.maxVertexAttrs = 0;
		cv.maxVaryingVectors = 0;
		cv.extensionsSupported = 0;
		cv.textureExtendedFeatures = 0;
		cv.textureColorTableSize = 0;
		cv.anisotropicDegreeMax = 0;
		cv.textureBoundaryWidthMax = 0;
		cv.textureWidthMax = 0;
		cv.textureHeightMax = 0;
		cv.texture3DWidthMax = 0;
		cv.texture3DHeightMax = 0;
		cv.texture3DDepthMax = 0;
		cv.shadingLanguageGLSL = false;

		// Now make queries and set up these fields
		String glVersion = gl.glGetString(GL2ES2.GL_VERSION);
		String glVendor = gl.glGetString(GL2ES2.GL_VENDOR);
		String glRenderer = gl.glGetString(GL2ES2.GL_RENDERER);
		cv.nativeGraphicsVersion = glVersion;
		cv.nativeGraphicsVendor = glVendor;
		cv.nativeGraphicsRenderer = glRenderer;

		// just for debug
		// System.out.println("***glVersion " +glVersion);
		// System.out.println("***glVendor " +glVendor);
		// System.out.println("***glRenderer " +glRenderer);
		// System.out.println( ctx.getGLContext().toString());

		// find out the version, major and minor version number
		int[] versionNumbers = extractVersionInfo(glVersion);
		int major = versionNumbers[0];
		int minor = versionNumbers[1];

		///////////////////////////////////////////
		// setup the graphics context properties //

		// NOTE: Java 3D now requires OpenGL 1.3 for full functionality.
		// For backwards compatibility with certain older graphics cards and
		// drivers (e.g., the Linux DRI driver for older ATI cards),
		// we will try to run on OpenGL 1.2 in an unsupported manner. However,
		// we will not attempt to use OpenGL extensions for any features that
		// are available in OpenGL 1.3, specifically multitexture, multisample,
		// and cube map textures.

		if (major < 1 || (major == 1 && minor < 2))
		{
			// In some double createNewContext uses or where getPreferredConfiguration is called before a Frame is constructed
			// the disabling of D3D can cause this issue
			// see Bug 1201 - Crash with option "sun.java2d.d3d=false"
			// https://jogamp.org/bugzilla/show_bug.cgi?id=1201

			// In the case of Java > 1.8u51 Win10  and an Intel HD2000/3000 driver, the driver will not load because 
			// the java.exe manifest has a "supportedOS Id" that the drivers don't like (the drivers are too old)
			// see  Bug 1278 - Windows 10 returns software Profile
			// https://jogamp.org/bugzilla/show_bug.cgi?id=1278
			// So we will detect Win10/Oracle Jre  u > 51 and offer advice to down grade jre

			if (glVendor.equalsIgnoreCase("Microsoft Corporation") && //
					glRenderer.equalsIgnoreCase("GDI Generic") && //
					glVersion.equalsIgnoreCase("1.1.0"))
			{
				System.err.println("Java3D - GDI Generic Driver use detected.");
				System.err.println("This may be caused by any of the following issues.");

				if (System.getProperty("sun.java2d.noddraw", "false").equals("true")
						|| System.getProperty("sun.java2d.d3d", "true").equals("false"))
				{
					System.err.println("Issue: Use of System.setProperty(\"sun.java2d.noddraw\", \"true\");");
					System.err.println("or System.setProperty(\"sun.java2d.d3d\", \"false\");");
					System.err.println("If either of these are being used please try either reversing or removing them,");
					System.err.println("or if they are required else where try adding System.setProperty(\"sun.awt.nopixfmt\", \"true\");");
				}

				if (Platform.getOSName().equalsIgnoreCase("Windows 10") && //win10
						(Platform.JAVA_VERSION_NUMBER.compareTo(new VersionNumber(1, 8, 0)) > 0) || // 1.9 or 1.8 > 51 //Version 19 was swapped to version 9 due to JEP 223 in jogl 2.4.0
						(Platform.JAVA_VERSION_NUMBER.compareTo(new VersionNumber(1, 8, 0)) == 0 && Platform.JAVA_VERSION_UPDATE > 51) && //
								Platform.getJavaVMName().toLowerCase().startsWith("java hotspot(tm)"))// e.g. Java HotSpot(TM) 64-Bit Server VM ; OpenJDK would give OpenJDK 64-Bit Server VM
				{
					System.err.println("Issue: The use of an Intel HD2000/3000 driver in combination with Windows 10 and");
					System.err.println("a JRE greater than 1.8 update 51. Please downgrade the JRE in use to JRE 1.8u51 or lower.");
					System.err.println("For more information please see https://jogamp.org/bugzilla/show_bug.cgi?id=1278.");
				}
				System.err.println("If this software has been supplied to you and you are unable to modify it's configuration");
				System.err.println("please contact the suppler of this software with this entire message.");
			}

			throw new IllegalRenderingStateException(
					"Java 3D ERROR : OpenGL 1.2 or better is required (GL_VERSION=" + major + "." + minor + ")");
		}

		boolean gl20 = false;
		boolean gl14 = false;
		boolean gl13 = false;

		if (major == 1)
		{
			if (minor == 2)
			{
				System.err.println("JAVA 3D: OpenGL 1.2 detected; will run with reduced functionality");
			}
			if (minor >= 3)
			{
				gl13 = true;
			}
			if (minor >= 4)
			{
				gl14 = true;
			}
		}
		else
		// major >= 2
		{
			gl13 = true;
			gl14 = true;
			gl20 = true;
		}

		if (gl20)
		{
			assert gl13;
			assert gl14;
			assert gl.isExtensionAvailable("GL_VERSION_2_0");
		}

		if (gl14)
		{
			assert gl13;
			assert gl.isExtensionAvailable("GL_VERSION_1_4");
		}

		if (gl13)
		{
			assert gl.isExtensionAvailable("GL_VERSION_1_3");
		}

		// Note that we don't query for GL_ARB_imaging here

		cv.textureExtendedFeatures |= Canvas3D.TEXTURE_LOD_RANGE;

		/*
		if (gl14)
		{
			cv.textureExtendedFeatures |= Canvas3D.TEXTURE_AUTO_MIPMAP_GENERATION;
		}*/

		// look for OpenGL 2.0 features
		// Fix to Issue 455 : Need to disable NPOT textures for older cards that claim to support it.
		// Some older cards (e.g., Nvidia fx500 and ATI 9800) claim to support OpenGL 2.0.
		// This means that these cards have to support non-power-of-two (NPOT) texture,
		// but their lack the necessary HW force the vendors the emulate this feature in software.
		// The result is a ~100x slower down compare to power-of-two textures.
		// Do not check for gl20 but instead check of GL_ARB_texture_non_power_of_two extension string
		// if (gl20) {
		//    if(!VirtualUniverse.mc.enforcePowerOfTwo) {
		//        cv.textureExtendedFeatures |= Canvas3D.TEXTURE_NON_POWER_OF_TWO;
		//    }
		// }

		// Setup GL_EXT_abgr
		if (gl.isExtensionAvailable("GL_EXT_abgr"))
		{
			cv.extensionsSupported |= Canvas3D.EXT_ABGR;
		}

		// GL_BGR is always supported
		cv.extensionsSupported |= Canvas3D.EXT_BGR;

		// Setup multisample
		// FIXME: this is not correct for the Windows platform yet
		// FIXME: this might be tricky, if I muck around the accum calls turn on again
		// ES2 has new enable/disable on GL_SAMPLE_ALPHA_TO_COVERAGE and GL_SAMPLE_COVERAGE
		// and GL2 and ES2 both have glSampleCoverage calls
		// Renderer line 1158 is the guy that goes for accum if this is not set
		if (gl13)
		{
			cv.extensionsSupported |= Canvas3D.MULTISAMPLE;
			ctx.setHasMultisample(true);
		}

		if ((cv.extensionsSupported & Canvas3D.MULTISAMPLE) != 0 && !VirtualUniverse.mc.implicitAntialiasing)
		{
			//with a bit of luck ES2 will ignore this call and leave sampling on
			//gl.glDisable(GL2ES2.GL_MULTISAMPLE);
		}

		checkTextureExtensions(cv, ctx, gl, gl13);

		checkGLSLShaderExtensions(cv, ctx, gl, gl13);

		cv.textureBoundaryWidthMax = 1;

		int[] tmp = new int[1];
		gl.glGetIntegerv(GL2ES2.GL_MAX_TEXTURE_SIZE, tmp, 0);
		cv.textureWidthMax = tmp[0];
		cv.textureHeightMax = tmp[0];
	}

	private static void disableAttribFor2D(GL2ES2 gl)
	{
		gl.glDisable(GL.GL_BLEND);
		gl.glDisable(GL.GL_CULL_FACE);
		gl.glDisable(GL.GL_DEPTH_TEST);
		gl.glDisable(GL.GL_POLYGON_OFFSET_FILL);
		gl.glDisable(GL.GL_STENCIL_TEST);
	}

	private static void disableAttribForRaster(GL2ES2 gl)
	{
		gl.glDisable(GL.GL_CULL_FACE);
		gl.glDisable(GL.GL_POLYGON_OFFSET_FILL);
	}

	// Not needed generally as transpose can be called on the interface with gl
	public static void copyTranspose(double[] src, double[] dst)
	{
		dst[0] = src[0];
		dst[1] = src[4];
		dst[2] = src[8];
		dst[3] = src[12];
		dst[4] = src[1];
		dst[5] = src[5];
		dst[6] = src[9];
		dst[7] = src[13];
		dst[8] = src[2];
		dst[9] = src[6];
		dst[10] = src[10];
		dst[11] = src[14];
		dst[12] = src[3];
		dst[13] = src[7];
		dst[14] = src[11];
		dst[15] = src[15];
	}

	@Override
	void clear(Context ctx, float r, float g, float b, boolean clearStencil)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.clear()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.clear++;

		Jogl2es2Context jctx = (Jogl2es2Context) ctx;
		GL2ES2 gl = jctx.gl2es2();

		// Mask of which buffers to clear, this always includes color & depth
		int clearMask = GL2ES2.GL_DEPTH_BUFFER_BIT | GL2ES2.GL_COLOR_BUFFER_BIT | GL2ES2.GL_STENCIL_BUFFER_BIT;

		// NOTE stencil always cleared

		gl.glDepthMask(true);
		gl.glClearColor(r, g, b, jctx.getAlphaClearValue());
		gl.glClear(clearMask);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
	}

	private static int createSimpleTextureShaderProgram(Context ctx)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.createSimpleTextureShaderProgram()");

		// now create and set shader
		String vertexProgram = "#version 120\n";
		vertexProgram += "attribute vec2 glMultiTexCoord0;\n";
		vertexProgram += "attribute vec4 glVertex;\n";
		vertexProgram += "varying vec2 glTexCoord0;\n";
		vertexProgram += "void main( void ){\n";
		vertexProgram += "gl_Position = glVertex;\n";
		vertexProgram += "glTexCoord0 = glMultiTexCoord0.st;\n";
		vertexProgram += "}";

		String fragmentProgram = "#version 120\n";
		fragmentProgram += "precision mediump float;\n";
		fragmentProgram += "varying vec2 glTexCoord0;\n";
		fragmentProgram += "uniform sampler2D BaseMap;\n";
		fragmentProgram += "void main( void ){\n ";
		fragmentProgram += "vec4 baseMap = texture2D( BaseMap, glTexCoord0.st );\n";
		fragmentProgram += "gl_FragColor = baseMap;\n";
		//fragmentProgram += "gl_FragColor = vec4(1,1,0.5,1);\n";
		fragmentProgram += "}";

		int programId = createShaderProgram(ctx, vertexProgram, fragmentProgram);

		// now record the locations
		Jogl2es2Context jctx = (Jogl2es2Context) ctx;
		GL2ES2 gl = jctx.gl2es2();
		jctx.simpleTextureShaderProgramVertLoc = gl.glGetAttribLocation(programId, "glVertex");
		jctx.simpleTextureShaderProgramTexCoordLoc = gl.glGetAttribLocation(programId, "glMultiTexCoord0");
		jctx.simpleTextureShaderProgramBaseMapLoc = gl.glGetUniformLocation(programId, "BaseMap");

		return programId;
	}

	private static int createShaderProgram(Context ctx, String vertexProgram, String fragmentProgram)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.createShaderProgram()");

		Jogl2es2Context jctx = (Jogl2es2Context) ctx;
		GL2ES2 gl = jctx.gl2es2();

		int shaderHandleV = gl.glCreateShader(GL2ES2.GL_VERTEX_SHADER);
		int shaderHandleF = gl.glCreateShader(GL2ES2.GL_FRAGMENT_SHADER);

		int shaderProgramHandle = gl.glCreateProgram();

		gl.glAttachShader(shaderProgramHandle, shaderHandleV);
		gl.glAttachShader(shaderProgramHandle, shaderHandleF);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

		gl.glShaderSource(shaderHandleV, 1, new String[] { vertexProgram }, null, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		gl.glCompileShader(shaderHandleV);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		int[] status = new int[1];
		gl.glGetShaderiv(shaderHandleV, GL2ES2.GL_COMPILE_STATUS, status, 0);
		if (status[0] == 0)
		{
			String detailMsg = getShaderInfoLog(gl, shaderHandleV);
			System.err.println(detailMsg);
		}
		gl.glShaderSource(shaderHandleF, 1, new String[] { fragmentProgram }, null, 0);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		gl.glCompileShader(shaderHandleF);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		gl.glGetShaderiv(shaderHandleF, GL2ES2.GL_COMPILE_STATUS, status, 0);
		if (status[0] == 0)
		{
			String detailMsg = getShaderInfoLog(gl, shaderHandleF);
			System.err.println(detailMsg);
		}

		gl.glLinkProgram(shaderProgramHandle);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

		gl.glGetProgramiv(shaderProgramHandle, GL2ES2.GL_LINK_STATUS, status, 0);
		if (status[0] == 0)
		{
			String detailMsg = getProgramInfoLog(gl, shaderProgramHandle);
			System.err.println(detailMsg);
		}

		return shaderProgramHandle;
	}

	/**
	 * Texture 0 must be bound by now
	 * @param texMinU 
	 */
	private static void renderTexturedQuad(Context ctx, float texMinU, float texMaxU, float texMinV, float texMaxV, float mapMinX,
			float mapMaxX, float mapMinY, float mapMaxY, float mapZ)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.renderTexturedQuad()");

		Jogl2es2Context jctx = (Jogl2es2Context) ctx;
		GL2ES2 gl = jctx.gl2es2();

		//NOTE .order(ByteOrder.nativeOrder())!!!
		int vcount = 6;
		FloatBuffer verts = ByteBuffer.allocateDirect(Float.SIZE / 8 * 3 * vcount).order(ByteOrder.nativeOrder()).asFloatBuffer();
		FloatBuffer tcs = ByteBuffer.allocateDirect(Float.SIZE / 8 * 2 * vcount).order(ByteOrder.nativeOrder()).asFloatBuffer();

		//CCW windings for fun (cull face should make unnecessary
		tcs.put(texMinU).put(texMinV);
		verts.put(mapMinX).put(mapMinY).put(mapZ);
		tcs.put(texMaxU).put(texMaxV);
		verts.put(mapMaxX).put(mapMaxY).put(mapZ);
		tcs.put(texMinU).put(texMaxV);
		verts.put(mapMinX).put(mapMaxY).put(mapZ);

		tcs.put(texMinU).put(texMinV);
		verts.put(mapMinX).put(mapMinY).put(mapZ);
		tcs.put(texMaxU).put(texMinV);
		verts.put(mapMaxX).put(mapMinY).put(mapZ);
		tcs.put(texMaxU).put(texMaxV);
		verts.put(mapMaxX).put(mapMaxY).put(mapZ);

		verts.position(0);
		tcs.position(0);

		int[] tmp = new int[2];
		gl.glGenBuffers(2, tmp, 0);
		int vertBufId = tmp[0];
		int tcBufId = tmp[1];
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, vertBufId);
		gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (verts.remaining() * Float.SIZE / 8), verts, GL2ES2.GL_STREAM_DRAW);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, tcBufId);
		gl.glBufferData(GL2ES2.GL_ARRAY_BUFFER, (tcs.remaining() * Float.SIZE / 8), tcs, GL2ES2.GL_STREAM_DRAW);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

		if (jctx.simpleTextureShaderProgramId == -1)
			jctx.simpleTextureShaderProgramId = createSimpleTextureShaderProgram(ctx);

		gl.glUseProgram(jctx.simpleTextureShaderProgramId);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);
		jctx.prevShaderProgram = jctx.simpleTextureShaderProgramId;

		boolean bindingRequired = true;

		// always create a new one
		int vaoId = -1;
		if (gl.isGL2ES3())
		{
			GL2ES3 gl2es3 = (GL2ES3)gl;
			if (vaoId == -1)
			{
				int[] tmp2 = new int[1];
				gl2es3.glGenVertexArrays(1, tmp2, 0);
				vaoId = tmp2[0];
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}
			else
			{
				bindingRequired = false;
			}
			gl2es3.glBindVertexArray(vaoId);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}

		if (bindingRequired)
		{
			if (jctx.simpleTextureShaderProgramVertLoc != -1)
			{
				gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, vertBufId);
				gl.glVertexAttribPointer(jctx.simpleTextureShaderProgramVertLoc, 3, GL2ES2.GL_FLOAT, false, 0, 0);
				gl.glEnableVertexAttribArray(jctx.simpleTextureShaderProgramVertLoc);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}

			if (jctx.simpleTextureShaderProgramTexCoordLoc != -1)
			{
				gl.glBindBuffer(GL2ES2.GL_ARRAY_BUFFER, tcBufId);
				gl.glVertexAttribPointer(jctx.simpleTextureShaderProgramTexCoordLoc, 2, GL2ES2.GL_FLOAT, false, 0, 0);
				gl.glEnableVertexAttribArray(jctx.simpleTextureShaderProgramTexCoordLoc);
				if (DO_OUTPUT_ERRORS)
					outputErrors(ctx);
			}
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}

		if (jctx.simpleTextureShaderProgramBaseMapLoc != -1)
		{
			gl.glUniform1i(jctx.simpleTextureShaderProgramBaseMapLoc, 0);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}

		gl.glDrawArrays(GL2ES2.GL_TRIANGLES, 0, vcount);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

		// clean u as we have to recreate each pass
		if (vaoId != -1)
			((GL2ES3)gl).glDeleteVertexArrays(1, new int[] { vaoId }, 0);

		if (vertBufId != -1)
			gl.glDeleteBuffers(1, new int[] { vertBufId }, 0);

		if (tcBufId != -1)
			gl.glDeleteBuffers(1, new int[] { tcBufId }, 0);
	}

	@Override
	void textureFillBackground(Context ctx, float texMinU, float texMaxU, float texMinV, float texMaxV, float mapMinX, float mapMaxX,
			float mapMinY, float mapMaxY, boolean useBilinearFilter)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.textureFillBackground()");

		Jogl2es2Context jctx = (Jogl2es2Context) ctx;
		GL2ES2 gl = jctx.gl2es2();

		disableAttribFor2D(gl);

		// Setup filter mode if needed 
		if (useBilinearFilter)
		{
			// System.err.println("JoglPipeline - Raster  : use bilinear filter\n");
			gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
			gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}

		//gl.glColor4f(1.0f, 1.0f, 1.0f, alpha);
		//TODO: alpha value!! make sure blending on and use the alpha value in shader

		gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

		//gl.glOrtho(0.0, 1.0, 0.0, 1.0, 0.0, 1.0); //note [0,1] not the normal [-1,1]

		// add a Z so we stay closer to textureFillRaster
		float mapZ = 0f;

		//NOTE!!!!!! very very well, tex*V is swapped max/min in order to upright the Y of the image!!
		renderTexturedQuad(ctx, texMinU, texMaxU, texMaxV, texMinV, mapMinX, mapMaxX, mapMinY, mapMaxY, mapZ);
	}

	@Override
	void textureFillRaster(Context ctx, float texMinU, float texMaxU, float texMinV, float texMaxV, float mapMinX, float mapMaxX,
			float mapMinY, float mapMaxY, float mapZ, float alpha, boolean useBilinearFilter)
	{

		if (VERBOSE)
			System.err.println("JoglPipeline.textureFillRaster()");

		Jogl2es2Context jctx = (Jogl2es2Context) ctx;
		GL2ES2 gl = jctx.gl2es2();

		disableAttribForRaster(gl);

		// Setup filter mode if needed 
		if (useBilinearFilter)
		{
			// System.err.println("JoglPipeline - Raster  : use bilinear filter\n");
			gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
			gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}

		//gl.glColor4f(1.0f, 1.0f, 1.0f, alpha);
		//TODO: alpha value!! make sure blending on and use the alpha value in shader

		gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
		if (DO_OUTPUT_ERRORS)
			outputErrors(ctx);

		//gl.glOrtho(0.0, 1.0, 0.0, 1.0, 0.0, 1.0); //note [0,1] not the normal [-1,1]

		//NOTE!!!!!! very very well, tex*V is swapped max/min in order to upright the Y of the image!!
		renderTexturedQuad(ctx, texMinU, texMaxU, texMaxV, texMinV, mapMinX, mapMaxX, mapMinY, mapMaxY, mapZ);

	}

	@Override
	void executeRasterDepth(Context ctx, float posX, float posY, float posZ, int srcOffsetX, int srcOffsetY, int rasterWidth,
			int rasterHeight, int depthWidth, int depthHeight, int depthFormat, Object depthData)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.executeRasterDepth()");
		
		//Jogl2es2Context jctx = (Jogl2es2Context) ctx;
		//GL2ES2 gl = jctx.gl2es2();
		
		throw new UnsupportedOperationException(
				"To get depth you should use a shader that return depth info for gl2es2 then read from color");
		/*
		gl.glRasterPos3f(posX, posY, posZ);
		
		int[] drawBuf = new int[1];
		gl.glGetIntegerv(GL2.GL_DRAW_BUFFER, drawBuf, 0);
		// disable draw buffer 
		gl.glDrawBuffer(GL.GL_NONE);
		
		
		 // raster position is upper left corner, default for Java3D
		 // ImageComponent currently has the data reverse in Y
		 
		gl.glPixelZoom(1.0f, -1.0f);
		gl.glPixelStorei(GL2.GL_UNPACK_ROW_LENGTH, depthWidth);
		if (srcOffsetX >= 0)
		{
			gl.glPixelStorei(GL2.GL_UNPACK_SKIP_PIXELS, srcOffsetX);
			if (srcOffsetX + rasterWidth > depthWidth)
			{
				rasterWidth = depthWidth - srcOffsetX;
			}
		}
		else
		{
			rasterWidth += srcOffsetX;
			if (rasterWidth > depthWidth)
			{
				rasterWidth = depthWidth;
			}
		}
		if (srcOffsetY >= 0)
		{
			gl.glPixelStorei(GL2.GL_UNPACK_SKIP_ROWS, srcOffsetY);
			if (srcOffsetY + rasterHeight > depthHeight)
			{
				rasterHeight = depthHeight - srcOffsetY;
			}
		}
		else
		{
			rasterHeight += srcOffsetY;
			if (rasterHeight > depthHeight)
			{
				rasterHeight = depthHeight;
			}
		}
		
		if (depthFormat == DepthComponentRetained.DEPTH_COMPONENT_TYPE_INT)
		{
			gl.glDrawPixels(rasterWidth, rasterHeight, GL2.GL_DEPTH_COMPONENT, GL.GL_UNSIGNED_INT, IntBuffer.wrap((int[]) depthData));
		}
		else
		{ // DepthComponentRetained.DEPTH_COMPONENT_TYPE_FLOAT 
			gl.glDrawPixels(rasterWidth, rasterHeight, GL2.GL_DEPTH_COMPONENT, GL.GL_FLOAT, FloatBuffer.wrap((float[]) depthData));
		}
		
		// re-enable draw buffer 
		gl.glDrawBuffer(drawBuf[0]);
		
		gl.glPixelStorei(GL2.GL_UNPACK_ROW_LENGTH, 0);
		gl.glPixelStorei(GL2.GL_UNPACK_SKIP_PIXELS, 0);
		gl.glPixelStorei(GL2.GL_UNPACK_SKIP_ROWS, 0);
		*/
	}

	/**
	 * This native method makes sure that the rendering for this canvas gets
	 * done now.
	 */
	@Override
	// render is it's own thread so finish stops nothing
	void syncRender(Context ctx, boolean wait)
	{

		if (VERBOSE)
			System.err.println("JoglPipeline.syncRender() " + wait);
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.syncRenderTime = System.nanoTime();

		// clean up any buffers that need freeing
		doClearBuffers(ctx);

		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).outputPerFrameData();

		// also seems to be ok, just do it as well
		if (!NEVER_RELEASE_CONTEXT)
		{
			GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
			if (wait)
				gl.glFinish();
			else
				gl.glFlush();
		}

	}

	// The native method for swapBuffers - onscreen only
	@Override
	void swapBuffers(Canvas3D cv, Context ctx, Drawable drawable)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.swapBuffers()");

		GLDrawable draw = drawable(drawable);
		draw.swapBuffers();

		((Jogl2es2Context) ctx).gl_state.clear();
	}

	private static void outputErrors(Context ctx)
	{
		if (DO_OUTPUT_ERRORS)
		{
			GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
			int err = gl.glGetError();
			if (err != GL2ES2.GL_NO_ERROR)
			{
				// GL_NO_ERROR = 0x0
				// GL_INVALID_ENUM = 0x500; 1280
				// GL_INVALID_VALUE = 0x501; 1281
				// GL_INVALID_OPERATION = 0x502; 1282
				// GL_INVALID_FRAMEBUFFER_OPERATION = 0x506; 1286
				// GL_OUT_OF_MEMORY= 0x505; 1285
				// GL_STACK_UNDERFLOW 503?
				// GL_STACK_OVERFLOW 504?

				// check for no current shader program (likely a switch between scenes or something)
				if (err == GL2ES2.GL_INVALID_OPERATION)
				{
					int[] res = new int[1];
					gl.glGetIntegerv(GL2ES2.GL_CURRENT_PROGRAM, res, 0);
					// 0 is no current program
					if (res[0] == 0)
						return;
				}

				System.err.println("JoglesPipeline GL error reported " + err);
				StackTraceElement[] st = new Throwable().getStackTrace();
				if (st.length > 1)
					System.err.println("Occured in " + st[1]);

				// seems to produce heaps?
				/*err = gl.glGetError();
				if (err != GL2ES2.GL_NO_ERROR)
				{
					System.err.println("woooh second error too! "+ err);
					err = gl.glGetError();
					if (err != GL2ES2.GL_NO_ERROR)
					{
						System.err.println("woooh third error too! "+ err);
					}
				}*/
			}
		}
	}

	// The native method that sets this ctx to be the current one
	@Override
	boolean useCtx(Context ctx, Drawable drawable)
	{
		if (!NEVER_RELEASE_CONTEXT || !currently_current)
		{
			if (VERBOSE)
				System.err.println("JoglPipeline.useCtx()**********************************");
			if (OUTPUT_PER_FRAME_STATS)
				((Jogl2es2Context) ctx).perFrameStats.useCtx++;

			GLContext context = context(ctx);

			if (context.getGLDrawable() == null)
				System.out.println("context.getGLDrawable() == null!");

			currently_current = true;
			int res = context.makeCurrent();
			return (res != GLContext.CONTEXT_NOT_CURRENT);
		}
		return true;
	}

	//only used for NEVER_RELEASE_CONTEXT, just to spot first call
	public static boolean currently_current = false;

	// Optionally release the context. Returns true if the context was released.
	@Override
	boolean releaseCtx(Context ctx)
	{
		if (!NEVER_RELEASE_CONTEXT)
		{
			if (VERBOSE)
				System.err.println("JoglPipeline.releaseCtx()");
			if (OUTPUT_PER_FRAME_STATS)
				((Jogl2es2Context) ctx).perFrameStats.releaseCtx++;
			GLContext context = context(ctx);

			if (context.isCurrent())
				context.release();
		}
		return true;
	}

	// ---------------------------------------------------------------------

	//
	// MasterControl methods
	//

	// Maximum lights supported by the native API
	@Override
	int getMaximumLights()
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.getMaximumLights()");

		// FIXME: this isn't quite what the NativePipeline returns but
		// is probably close enough
		return 8;
	}
	
	// This is the native for reading the image from the offscreen buffer
	@Override
	void readOffScreenBuffer(Canvas3D cv, Context ctx, int format, int dataType, Object data, int width, int height)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.readOffScreenBuffer()");

		GLDrawable glDrawable = ((JoglDrawable) cv.drawable).getGLDrawable();
		GLCapabilitiesImmutable chosenCaps = glDrawable.getChosenGLCapabilities();
		GLFBODrawable fboDrawable = null;

		GL2ES2 gl = context(ctx).getGL().getGL2ES2();

		// If FBO
		if (chosenCaps.isFBO())
		{

			fboDrawable = (GLFBODrawable) glDrawable;

			if (chosenCaps.getDoubleBuffered())
			{
				// swap = resolve multisampling or flip back/front FBO
				fboDrawable.swapBuffers();
				// unbind texture render target, we read from FBO
				gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
			}

			// bind FBO for reading pixel data
			// GL_FRONT = SamplingSinkFBO if double buffered and multisampled
			// GL_FRONT if double buffered ( = GL_BAck before swap was called)
			// GL_FRONT = GL_BACK if single buffered (single FBO)

			fboDrawable.getFBObject(GL.GL_FRONT).bind(gl);
		}
		// else pbuffer

		//gl.glPixelStorei(GL2.GL_PACK_ROW_LENGTH, width);
		gl.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);

		int type = 0;

		if ((dataType == ImageComponentRetained.IMAGE_DATA_TYPE_BYTE_ARRAY)
				|| (dataType == ImageComponentRetained.IMAGE_DATA_TYPE_BYTE_BUFFER))
		{

			switch (format)
			{
			// GL_BGR
			case ImageComponentRetained.TYPE_BYTE_BGR:
				type = GL2.GL_BGR;// not ok
				break;
			case ImageComponentRetained.TYPE_BYTE_RGB:
				type = GL.GL_RGB;//ok
				break;
			// GL_ABGR_EXT
			case ImageComponentRetained.TYPE_BYTE_ABGR:
				if (isExtensionAvailable.GL_EXT_abgr(gl))
				{ // If false, should never come here!  
					type = GL2.GL_ABGR_EXT; //ok
				}
				else
				{
					assert false;
					return;
				}
				break;
			case ImageComponentRetained.TYPE_BYTE_RGBA:
				type = GL.GL_RGBA;// this a valid case for GL2ES2
				break;

			/*
			 * This method only supports 3 and 4 components formats and BYTE
			 * types.
			 */
			case ImageComponentRetained.TYPE_BYTE_LA:
			case ImageComponentRetained.TYPE_BYTE_GRAY:
			case ImageComponentRetained.TYPE_USHORT_GRAY:
			case ImageComponentRetained.TYPE_INT_BGR:
			case ImageComponentRetained.TYPE_INT_RGB:
			case ImageComponentRetained.TYPE_INT_ARGB:
			default:
				throw new AssertionError("illegal format " + format);
			}

			ByteBuffer buf = null;
			if (dataType == ImageComponentRetained.IMAGE_DATA_TYPE_BYTE_ARRAY)
			{
				buf = ByteBuffer.wrap((byte[]) data);
			}
			else
			{
				buf = (ByteBuffer) data;
			}
			
			gl.glReadPixels(0, 0, width, height, type, GL.GL_UNSIGNED_BYTE, buf);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}
		else if ((dataType == ImageComponentRetained.IMAGE_DATA_TYPE_INT_ARRAY)
				|| (dataType == ImageComponentRetained.IMAGE_DATA_TYPE_INT_BUFFER))
		{
			switch (format)
			{
			// GL_BGR
			case ImageComponentRetained.TYPE_INT_BGR:
				type = GL2.GL_BGR;// not ok
				break;
			case ImageComponentRetained.TYPE_INT_RGB:
				type = GL.GL_RGB;//ok
				break;			
			case ImageComponentRetained.TYPE_INT_ARGB:
				type = GL.GL_RGBA;// this a valid case for GL2ES2
				break;

			/*
			 * This method only supports 3 and 4 components formats and INT
			 * types.
			 */
			case ImageComponentRetained.TYPE_BYTE_LA:
			case ImageComponentRetained.TYPE_BYTE_GRAY:
			case ImageComponentRetained.TYPE_USHORT_GRAY:
			case ImageComponentRetained.TYPE_BYTE_BGR:
			case ImageComponentRetained.TYPE_BYTE_RGB:
			case ImageComponentRetained.TYPE_BYTE_RGBA:
			default:
				throw new AssertionError("illegal format " + format);
			}

			IntBuffer buf = null;
			if (dataType == ImageComponentRetained.IMAGE_DATA_TYPE_INT_ARRAY)
			{
				buf = IntBuffer.wrap((int[]) data);
			}
			else
			{
				buf = (IntBuffer) data;
			}
			
			gl.glReadPixels(0, 0, width, height, type, GL.GL_UNSIGNED_BYTE, buf);
			if (DO_OUTPUT_ERRORS)
				outputErrors(ctx);
		}
		else
		{
			throw new AssertionError("illegal image data type " + dataType + " Try creating a BufferedImage of type TYPE_3BYTE_BGR");
		}

		// If FBO
		if (chosenCaps.isFBO())
		{
			// bind FBO for drawing
			fboDrawable.getFBObject(GL.GL_BACK).bind(gl);
		}
	}

	// ----------------------- Below here are initialization methods

	// Number of milliseconds to wait for windows to pop up on screen
	private static final int WAIT_TIME = 1000;
	// Configurable constant just in case we want to change this later
	private static final int MIN_FRAME_SIZE = 1;

	private GLProfile profile;


	// This is the native method for creating the underlying graphics context.
	@Override
	Context createNewContext(Canvas3D cv, Drawable drawable, Context shareCtx, boolean isSharedCtx, boolean offScreen)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.createNewContext()");

		GLDrawable glDrawable = null;
		GLContext glContext = null;

		if (offScreen)
		{
			glDrawable = drawable(cv.drawable); // cv.drawable != null, set in			
			// 'createOffScreenBuffer'			
			glContext = glDrawable.createContext(context(shareCtx));
		}
		else
		{
			// determined in 'getBestConfiguration'
			GraphicsConfigInfo gcInf0 = Canvas3D.graphicsConfigTable.get(cv.graphicsConfiguration);
			AWTGraphicsConfiguration awtConfig = (AWTGraphicsConfiguration) gcInf0.getPrivateData();

			// JAWTWindow
			JAWTWindow nativeWindow = (JAWTWindow) NativeWindowFactory.getNativeWindow(cv, awtConfig);
			nativeWindow.lockSurface();
			try
			{
				glDrawable = GLDrawableFactory.getFactory(profile).createGLDrawable(nativeWindow);
				glContext = glDrawable.createContext(context(shareCtx));
			}
			finally
			{
				nativeWindow.unlockSurface();
			}

			cv.drawable = new JoglDrawable(glDrawable, nativeWindow);
		}

		// assuming that this only gets called after addNotify has been called
		if (!glDrawable.isRealized())
			glDrawable.setRealized(true);

		// Apparently we are supposed to make the context current at this point
		// and set up a bunch of properties

		// Work around for some low end graphics driver bug, such as Intel
		// Chipset.
		// Issue 324 : Lockup J3D program and throw exception using JOGL
		// renderer
		boolean failed = false;
		int failCount = 0;
		int MAX_FAIL_COUNT = 5;
		do
		{
			failed = false;
			int res = glContext.makeCurrent();
			if (res == GLContext.CONTEXT_NOT_CURRENT)
			{
				// System.err.println("makeCurrent fail : " + failCount);
				failed = true;
				++failCount;
				try
				{
					Thread.sleep(100);
				}
				catch (InterruptedException e)
				{
				}
			}
		}
		while (failed && (failCount < MAX_FAIL_COUNT));

		if (failCount == MAX_FAIL_COUNT)
		{
			throw new IllegalRenderingStateException("Unable to make new context current after " + failCount + "tries");
		}

		GL2ES2 gl = glContext.getGL().getGL2ES2();

		Jogl2es2Context ctx = new Jogl2es2Context(glContext);

		try
		{
			if (!getPropertiesFromCurrentContext(ctx, gl))
			{
				throw new IllegalRenderingStateException("Unable to fetch properties from current OpenGL context");
			}

			if (!isSharedCtx)
			{
				// Set up fields in Canvas3D
				setupCanvasProperties(cv, ctx, gl);
			}

			// Enable rescale normal
			// gl.glEnable(GL2.GL_RESCALE_NORMAL);

			// gl.glColorMaterial(GL.GL_FRONT_AND_BACK, GL2.GL_DIFFUSE);
			gl.glDepthFunc(GL.GL_LEQUAL);
			// gl.glEnable(GL2.GL_COLOR_MATERIAL);

			/*
			 * OpenGL specs: glReadBuffer specifies a color buffer as the source
			 * for subsequent glReadPixels. This source mode is initially
			 * GL_FRONT in single-buffered and GL_BACK in double-buffered
			 * configurations.
			 * 
			 * We leave this mode unchanged in on-screen rendering and adjust it
			 * in off-screen rendering. See below.
			 */
			// gl.glReadBuffer(GL_FRONT); // off window, default for
			// single-buffered non-stereo window

			// Issue 417: JOGL: Mip-mapped NPOT textures rendered incorrectly
			// J3D images are aligned to 1 byte
			gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);

			// Workaround for issue 400: Enable separate specular by default
			// gl.glLightModeli(GL2.GL_LIGHT_MODEL_COLOR_CONTROL,
			// GL2.GL_SEPARATE_SPECULAR_COLOR);

			// Mac OS X / JRE 7 : onscreen rendering = offscreen rendering
			// bind FBO
			if (!offScreen && glDrawable instanceof GLFBODrawable)
			{
				GLFBODrawable fboDrawable = (GLFBODrawable) glDrawable;
				// bind GLFBODrawable's drawing FBObject
				// GL_BACK returns the correct FBOObject for single/double
				// buffering, incl. multisampling
				fboDrawable.getFBObject(GL.GL_BACK).bind(gl);
			}

			// FBO or pbuffer
			if (offScreen)
			{

				// Final caps
				GLCapabilitiesImmutable chosenCaps = glDrawable.getChosenGLCapabilities();

				// FBO
				if (glDrawable instanceof GLFBODrawable)
				{
					GLFBODrawable fboDrawable = (GLFBODrawable) glDrawable;
					// bind GLFBODrawable's drawing FBObject
					// GL_BACK returns the correct FBOObject for single/double
					// buffering, incl. multisampling
					fboDrawable.getFBObject(GL.GL_BACK).bind(gl);
				}
				// pbuffer
				else
				{
					// Double buffering: read from back buffer, as we don't swap
					// Even this setting is identical to the initially mode it
					// is set explicitly
					/*
					 * if (chosenCaps.getDoubleBuffered()) {
					 * gl.glReadBuffer(GL.GL_BACK); } else {
					 * gl.glReadBuffer(GL.GL_FRONT); }
					 */
				}
			}
		}
		finally
		{
			glContext.release();
		}

		return ctx;
	}

	@Override
	void createQueryContext(Canvas3D cv, Drawable drawable, boolean offScreen, int width, int height)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.createQueryContext()");

		// Assumes createQueryContext is never called for a drawable != null

		if (offScreen)
		{

			Drawable offDrawable = createOffScreenBuffer(cv, null, width, height);

			GLDrawable glDrawable = drawable(offDrawable);

			glDrawable.setRealized(true);

			GLContext glContext = glDrawable.createContext(null);
			glContext.makeCurrent();

			Jogl2es2Context ctx = new Jogl2es2Context(glContext);

			GL2ES2 gl = glContext.getGL().getGL2ES2();

			// get current context properties
			getPropertiesFromCurrentContext(ctx, gl);
			// Set up fields in Canvas3D
			setupCanvasProperties(cv, ctx, gl);

			// Done !

			glContext.release();
			glContext.destroy();
			glDrawable.setRealized(false);
		}
		else
		{

			// TODO can't find an implementation which avoids the use of
			// QueryCanvas
			// JOGL requires a visible Frame for an onscreen context
			Frame f = new Frame();
			Dialog d = new Dialog(f);
			d.setUndecorated(true);
			d.setLayout(new BorderLayout());

			ContextQuerier querier = new ContextQuerier(cv);

			AWTGraphicsConfiguration awtConfig = (AWTGraphicsConfiguration) Canvas3D.graphicsConfigTable.get(cv.graphicsConfiguration)
					.getPrivateData();

			QueryCanvas canvas = new QueryCanvas(awtConfig, querier);

			d.add(canvas, BorderLayout.CENTER);
			d.setSize(MIN_FRAME_SIZE, MIN_FRAME_SIZE);
			d.setVisible(true);
			canvas.doQuery();
			// Attempt to wait for the frame to become visible, but don't block
			// the EDT
			if (!EventQueue.isDispatchThread())
			{
				synchronized (querier)
				{
					if (!querier.done())
					{
						try
						{
							querier.wait(WAIT_TIME);
						}
						catch (InterruptedException e)
						{
						}
					}
				}
			}

			disposeOnEDT(d);
			disposeOnEDT(f);
		}
	}

	//Offscreen rendering methods below -----------------------
	static boolean isOffscreenLayerSurfaceEnabled(Canvas3D cv)
	{
		if (cv.drawable == null || cv.offScreen)
			return false;

		JoglDrawable joglDrawble = (JoglDrawable) cv.drawable;
		if (joglDrawble.getNativeWindow() instanceof OffscreenLayerOption)
		{
			OffscreenLayerOption olo = (OffscreenLayerOption) joglDrawble.getNativeWindow();
			if (olo == null)
				return false;
			else
				return olo.isOffscreenLayerSurfaceEnabled();
		}
		return false;
	}

	static boolean hasFBObjectSizeChanged(JoglDrawable jdraw, int width, int height)
	{
		if (!(jdraw.getGLDrawable() instanceof GLFBODrawable))
			return false;

		FBObject fboBack = ((GLFBODrawable) jdraw.getGLDrawable()).getFBObject(GL.GL_BACK);
		if (fboBack == null)
			return false;

		return (width != fboBack.getWidth() || height != fboBack.getHeight());
	}

	// Mac/JRE 7; called from Renderer when resizing is detected
	// Implementation follows the approach in
	// jogamp.opengl.GLDrawableHelper.resizeOffscreenDrawable(..)
	@Override
	void resizeOffscreenLayer(Canvas3D cv, int cvWidth, int cvHeight)
	{
		if (!isOffscreenLayerSurfaceEnabled(cv))
			return;

		JoglDrawable joglDrawable = (JoglDrawable) cv.drawable;
		if (!hasFBObjectSizeChanged(joglDrawable, cvWidth, cvHeight))
			return;

		int newWidth = Math.max(1, cvWidth);
		int newHeight = Math.max(1, cvHeight);

		GLDrawable glDrawble = joglDrawable.getGLDrawable();
		GLContext glContext = context(cv.ctx);

		// Assuming glContext != null

		final NativeSurface surface = glDrawble.getNativeSurface();
		final ProxySurface proxySurface = (surface instanceof ProxySurface) ? (ProxySurface) surface : null;

		final int lockRes = surface.lockSurface();

		try
		{
			// propagate new size - seems not relevant here
			if (proxySurface != null)
			{
				final UpstreamSurfaceHook ush = proxySurface.getUpstreamSurfaceHook();
				if (ush instanceof UpstreamSurfaceHook.MutableSize)
				{
					((UpstreamSurfaceHook.MutableSize) ush).setSurfaceSize(newWidth, newHeight);
				}
			}
			/*
			 else if(DEBUG) { 
			 // we have to assume surface contains the new size already, hence size check @ bottom 
			 System.err.println("GLDrawableHelper.resizeOffscreenDrawable: Drawable's offscreen surface n.a. ProxySurface, but "
			 +ns.getClass().getName()+": "+ns); }
			 */

			GL2ES2 gl = glContext.getGL().getGL2ES2();

			// FBO : should be the default case on Mac OS X
			if (glDrawble instanceof GLFBODrawable)
			{

				// Resize GLFBODrawable
				// TODO msaa gets lost
				// ((GLFBODrawable)glDrawble).resetSize(gl);

				// Alternative: resize GL_BACK FBObject directly,
				// if multisampled the FBO sink (GL_FRONT) will be resized
				// before the swap is executed
				int numSamples = ((GLFBODrawable) glDrawble).getChosenGLCapabilities().getNumSamples();
				FBObject fboObjectBack = ((GLFBODrawable) glDrawble).getFBObject(GL.GL_BACK);
				fboObjectBack.reset(gl, newWidth, newHeight, numSamples/* , false */); // false = don't reset
				// SamplingSinkFBO
				// immediately
				fboObjectBack.bind(gl);

				// If double buffered without antialiasing the GL_FRONT FBObject
				// will be resized by glDrawble after the next swap-call
			}
			// pbuffer - not tested because Mac OS X 10.7+ supports FBO
			else
			{
				// Create new GLDrawable (pbuffer) and update the coresponding
				// GLContext

				final GLContext currentContext = GLContext.getCurrent();
				final GLDrawableFactory factory = glDrawble.getFactory();

				// Ensure to sync GL command stream
				if (currentContext != glContext)
				{
					glContext.makeCurrent();
				}
				gl.glFinish();
				glContext.release();

				if (proxySurface != null)
				{
					proxySurface.enableUpstreamSurfaceHookLifecycle(false);
				}

				try
				{
					glDrawble.setRealized(false);
					// New GLDrawable
					glDrawble = factory.createGLDrawable(surface);
					glDrawble.setRealized(true);

					joglDrawable.setGLDrawable(glDrawble);
				}
				finally
				{
					if (proxySurface != null)
					{
						proxySurface.enableUpstreamSurfaceHookLifecycle(true);
					}
				}

				glContext.setGLDrawable(glDrawble, true); // re-association

				// make current last current context
				if (currentContext != null)
				{
					currentContext.makeCurrent();
				}
			}
		}
		finally
		{
			surface.unlockSurface();
		}
	}

	// This is the native for creating an offscreen buffer
	@Override
	Drawable createOffScreenBuffer(Canvas3D cv, Context ctx, int width, int height)
	{

		//OK general problem, 2 calls to setOffscreen buffer o a canvas3d will call this method once, attaching a
		// a new drawable all good, but
		// will then call makeNewContext twice using that same drawable, and that drawable doesn't like make current called twice

		if (VERBOSE)
			System.err.println("JoglPipeline.createOffScreenBuffer()");

		// ctx unused, doesn't exist yet

		// Offscreen Canvas3D's JoglGraphicsConfiguration
		JoglGraphicsConfiguration jgc = (JoglGraphicsConfiguration) cv.graphicsConfiguration;

		// Retrieve the offscreen Canvas3D's GraphicsConfigInfo
		GraphicsConfigInfo gcInf0 = Canvas3D.graphicsConfigTable.get(jgc);

		// Offscreen Canvas3D's graphics configuration, determined in
		// 'getBestConfiguration'
		AWTGraphicsConfiguration awtConfig = (AWTGraphicsConfiguration) gcInf0.getPrivateData();

		// TODO Offscreen Canvas3D's graphics devise, determined in
		// 'getBestConfiguration'
		// AbstractGraphicsDevice device = awtConfig.getScreen().getDevice(); //
		// throws exception
		// Alternative: default graphics device
		AbstractGraphicsDevice device = GLDrawableFactory.getDesktopFactory().getDefaultDevice();

		// Offscreen Canvas3D's capabilites, determined in
		// 'getBestConfiguration'
		GLCapabilities canvasCaps = (GLCapabilities) awtConfig.getChosenCapabilities();

		// For further investigations : the user's GraphicsConfigTemplate3D (not
		// used yet)
		GraphicsConfigTemplate3D gct3D = gcInf0.getGraphicsConfigTemplate3D();

		// Assuming that the offscreen drawable will/can support the chosen
		// GLCapabilities
		// of the offscreen Canvas3D

		final GLCapabilities offCaps = new GLCapabilities(profile);
		offCaps.copyFrom(canvasCaps);

		// double bufffering only if scene antialiasing is required/preferred
		// and supported
		if (offCaps.getSampleBuffers() == false)
		{
			offCaps.setDoubleBuffered(false);
			offCaps.setNumSamples(0);
		}

		// Never stereo
		offCaps.setStereo(false);

		// Set preferred offscreen drawable : framebuffer object (FBO) or
		// pbuffer
		offCaps.setFBO(true); // switches to pbuffer if FBO is not supported
		// caps.setPBuffer(true);

		// !! a 'null' capability chooser; JOGL doesn't call a chooser for
		// offscreen drawable

		// If FBO : 'offDrawable' is of type com.jogamp.opengl.GLFBODrawable
		GLDrawable offDrawable = GLDrawableFactory.getFactory(profile).createOffscreenDrawable(device, offCaps, null, width, height);

		// !! these chosen caps are not final as long as the corresponding
		// context is made current
		// System.out.println("createOffScreenBuffer chosenCaps = " +
		// offDrawable.getChosenGLCapabilities());

		return new JoglDrawable(offDrawable, null);
	}

	// 'destroyContext' is called first if context exists
	@Override
	void destroyOffScreenBuffer(Canvas3D cv, Context ctx, Drawable drawable)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.destroyOffScreenBuffer()");

		// it is done in 'destroyContext'
	}


	// Setup the full scene antialising in D3D and ogl when GL_ARB_multisamle supported
	@Override
	// looks like one time call in renderer.doWork
	void setFullSceneAntialiasing(Context ctx, boolean enable)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.setFullSceneAntialiasing()");
		if (OUTPUT_PER_FRAME_STATS)
			((Jogl2es2Context) ctx).perFrameStats.setFullSceneAntialiasing++;

		JoglContext joglctx = (JoglContext) ctx;
		GL2ES2 gl = ((Jogl2es2Context) ctx).gl2es2();
		// not supported in ES2, possibly just part of context generally
		// http://stackoverflow.com/questions/27035893/antialiasing-in-opengl-es-2-0
		// FIXME: This is working under GL2ES2 but will need to change I think
		// https://github.com/adrian110288/gdc2011-android-opengl/blob/master/src/com/example/gdc11/GDC11Activity.java

		if (joglctx.getHasMultisample() && !VirtualUniverse.mc.implicitAntialiasing)
		{
			if (enable)
			{
				System.out.println("I just set MULTISAMPLE just then");
				gl.glEnable(GL2ES2.GL_MULTISAMPLE);
			}
			else
			{
				gl.glDisable(GL2ES2.GL_MULTISAMPLE);
			}
		}
	}

	// Native method to update separate specular color control
	// looks like a one time call at the start of renderer.doWork
	@Override
	void updateSeparateSpecularColorEnable(Context ctx, boolean enable)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.updateSeparateSpecularColorEnable()");

		/*	GL2 gl = context(ctx).getGL().getGL2();
			//GL2ES2 gl = context(ctx).getGL().getGL2ES2();
			// bound to be not supported as definately shader work now
		
			if (enable)
			{
				gl.glLightModeli(GL2ES2.GL_LIGHT_MODEL_COLOR_CONTROL, GL2ES2.GL_SEPARATE_SPECULAR_COLOR);
			}
			else
			{
				gl.glLightModeli(GL2ES2.GL_LIGHT_MODEL_COLOR_CONTROL, GL2ES2.GL_SINGLE_COLOR);
			}*/
	}

	// ---------------------------------------------------------------------

	//
	// Canvas3D methods - native wrappers
	//

	@Override
	void destroyContext(Drawable drawable, Context ctx)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.destroyContext()");

		JoglDrawable joglDrawable = (JoglDrawable) drawable;
		GLContext context = context(ctx);

		// google below and see its a bug on marshmallow
		// 04-19 00:33:36.941 18278-18322/com.ingenieur.ese.eseandroid E/Surface: getSlotFromBufferLocked: unknown buffer: 0xb8dc6060
		// 04-19 00:33:37.182 18278-18278/com.ingenieur.ese.eseandroid D/JogAmp.NEWT: onStop.0

		// after this a restart and a swapBuffers call
		// gl_window.getDelegatedDrawable().swapBuffers();
		// gets a
		// com.jogamp.opengl.GLException: Error swapping buffers, eglError 0x300d, jogamp.opengl.egl.EGLDrawable[realized true,

		if (joglDrawable != null)
		{
			if (GLContext.getCurrent() == context)
			{
				context.release();
			}
			context.destroy();

			// assuming this is the right point at which to make this call
			joglDrawable.getGLDrawable().setRealized(false);

			joglDrawable.destroyNativeWindow();
		}
	}

	// This is the native method for getting the number of lights the underlying
	// native library can support.
	@Override
	int getNumCtxLights(Context ctx)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.getNumCtxLights()");

		/*	GL2ES2 gl = context(ctx).getGL().getGL2ES2();
			int[] res = new int[1];
			gl.glGetIntegerv(GL2ES2.GL_MAX_LIGHTS, res, 0);
			return res[0];*/

		// lights are now me! this is not called anyway
		return 8;
	}

	// True under Solaris,
	// False under windows when display mode <= 8 bit
	@Override
	// probably pointless?
	boolean validGraphicsMode()
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.validGraphicsMode()");

		// FIXME: believe this should do exactly what the native code
		// used to, but not 100% sure (also in theory should only run
		// this code on the Windows platform? What about Mac OS X?)
		/*		DisplayMode currentMode = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode();
				// Note: on X11 platforms, a bit depth < 0 simply indicates that
				// multiple visuals are supported on the current display mode
		
				if (VERBOSE)
					System.err.println("  Returning " + (currentMode.getBitDepth() < 0 || currentMode.getBitDepth() > 8));
		
				return (currentMode.getBitDepth() < 0 || currentMode.getBitDepth() > 8);*/

		return true;
	}

	// Native method for eye lighting
	@Override
	void ctxUpdateEyeLightingEnable(Context ctx, boolean localEyeLightingEnable)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.ctxUpdateEyeLightingEnable()");

		// lighting is entirely in shaders now so this should affect nothing?

		/*	GL2 gl = context(ctx).getGL().getGL2();
			//GL2ES2 gl = context(ctx).getGL().getGL2ES2();
		
			if (localEyeLightingEnable)
			{
				gl.glLightModeli(GL2ES2.GL_LIGHT_MODEL_LOCAL_VIEWER, GL2ES2.GL_TRUE);
			}
			else
			{
				gl.glLightModeli(GL2ES2.GL_LIGHT_MODEL_LOCAL_VIEWER, GL2ES2.GL_FALSE);
			}*/
	}
	// ---------------------------------------------------------------------

	//
	// DrawingSurfaceObject methods
	//

	// Method to construct a new DrawingSurfaceObject
	@Override
	DrawingSurfaceObject createDrawingSurfaceObject(Canvas3D cv)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.createDrawingSurfaceObject()");
		return new JoglDrawingSurfaceObject(cv);
	}

	// Method to free the drawing surface object
	@Override
	// NOOP
	void freeDrawingSurface(Canvas3D cv, DrawingSurfaceObject drawingSurfaceObject)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.freeDrawingSurface()");
		// This method is a no-op
	}

	// Method to free the native drawing surface object
	@Override
	// NOOP
	void freeDrawingSurfaceNative(Object o)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.freeDrawingSurfaceNative()");
		// This method is a no-op
	}

	// ----------------------------------------------------------------------
	// Context-related routines
	//

	// Helper used everywhere
	// USED heaps
	private static GLContext context(Context ctx)
	{
		if (ctx == null)
			return null;
		return ((JoglContext) ctx).getGLContext();
	}

	// Helper used everywhere
	// USED a small amount
	private static GLDrawable drawable(Drawable drawable)
	{
		if (drawable == null)
			return null;
		return ((JoglDrawable) drawable).getGLDrawable();
	}

	// ----------------------------------------------------------------------
	// General helper routines
	//
	private static ThreadLocal<ShortBuffer> nioIndexTemp = new ThreadLocal<ShortBuffer>();
	private static ThreadLocal<FloatBuffer> nioVertexTemp = new ThreadLocal<FloatBuffer>();
	private static ThreadLocal<DoubleBuffer> nioVertexDoubleTemp = new ThreadLocal<DoubleBuffer>();
	private static ThreadLocal<FloatBuffer> nioColorTemp = new ThreadLocal<FloatBuffer>();
	private static ThreadLocal<ByteBuffer> nioColorByteTemp = new ThreadLocal<ByteBuffer>();
	private static ThreadLocal<FloatBuffer> nioNormalTemp = new ThreadLocal<FloatBuffer>();
	private static ThreadLocal<FloatBuffer[]> nioTexCoordSetTemp = new ThreadLocal<FloatBuffer[]>();
	private static ThreadLocal<FloatBuffer[]> nioVertexAttrSetTemp = new ThreadLocal<FloatBuffer[]>();

	// I think these are not used often as nio buffers sort it out
	// but they are used a bit mind you
	
	private static ShortBuffer getIndexArrayBuffer(int[] indexArray)
	{
		return getIndexArrayBuffer(indexArray, true);
	}

	private static ShortBuffer getIndexArrayBuffer(int[] indexArray, boolean copyData)
	{
		return getNIOBuffer(indexArray, nioIndexTemp, copyData);
	}
	
	private static FloatBuffer getVertexArrayBuffer(float[] vertexArray)
	{
		return getVertexArrayBuffer(vertexArray, true);
	}

	private static FloatBuffer getVertexArrayBuffer(float[] vertexArray, boolean copyData)
	{
		return getNIOBuffer(vertexArray, nioVertexTemp, copyData);
	}

	private static DoubleBuffer getVertexArrayBuffer(double[] vertexArray)
	{
		return getVertexArrayBuffer(vertexArray, true);
	}

	private static DoubleBuffer getVertexArrayBuffer(double[] vertexArray, boolean copyData)
	{
		return getNIOBuffer(vertexArray, nioVertexDoubleTemp, true);
	}

	private static FloatBuffer getColorArrayBuffer(float[] colorArray)
	{
		return getColorArrayBuffer(colorArray, true);
	}

	private static FloatBuffer getColorArrayBuffer(float[] colorArray, boolean copyData)
	{
		return getNIOBuffer(colorArray, nioColorTemp, true);
	}

	private static ByteBuffer getColorArrayBuffer(byte[] colorArray)
	{
		return getColorArrayBuffer(colorArray, true);
	}

	private static ByteBuffer getColorArrayBuffer(byte[] colorArray, boolean copyData)
	{
		return getNIOBuffer(colorArray, nioColorByteTemp, true);
	}

	private static FloatBuffer getNormalArrayBuffer(float[] normalArray)
	{
		return getNormalArrayBuffer(normalArray, true);
	}

	private static FloatBuffer getNormalArrayBuffer(float[] normalArray, boolean copyData)
	{
		return getNIOBuffer(normalArray, nioNormalTemp, true);
	}

	private static FloatBuffer[] getTexCoordSetBuffer(Object[] texCoordSet)
	{
		return getNIOBuffer(texCoordSet, nioTexCoordSetTemp);
	}

	private static FloatBuffer[] getVertexAttrSetBuffer(Object[] vertexAttrSet)
	{
		return getNIOBuffer(vertexAttrSet, nioVertexAttrSetTemp);
	}
	
	//NOTE! int array copied, ShortBuffer returned
		private static ShortBuffer getNIOBuffer(int[] array, ThreadLocal<ShortBuffer> threadLocal, boolean copyData)
		{
			if (array == null)
			{
				return null;
			}
			ShortBuffer buf = threadLocal.get();
			if (buf == null)
			{
				buf = Buffers.newDirectShortBuffer(array.length);
				threadLocal.set(buf);
			}
			else
			{
				buf.rewind();
				if (buf.remaining() < array.length)
				{
					int newSize = Math.max(2 * buf.remaining(), array.length);
					buf = Buffers.newDirectShortBuffer(newSize);
					threadLocal.set(buf);
				}
			}
			if (copyData)
			{
				//NOTE! int array copied			
				short[] shorts = new short[array.length];
				for(int i=0;i<array.length;i++)
					shorts[i] = (short)array[i];
				
				buf.put(shorts);
				buf.rewind();
				buf.limit(array.length);
			}
			return buf;
		}

	private static FloatBuffer getNIOBuffer(float[] array, ThreadLocal<FloatBuffer> threadLocal, boolean copyData)
	{
		if (array == null)
		{
			return null;
		}
		FloatBuffer buf = threadLocal.get();
		if (buf == null)
		{
			buf = Buffers.newDirectFloatBuffer(array.length);
			threadLocal.set(buf);
		}
		else
		{
			buf.rewind();
			if (buf.remaining() < array.length)
			{
				int newSize = Math.max(2 * buf.remaining(), array.length);
				buf = Buffers.newDirectFloatBuffer(newSize);
				threadLocal.set(buf);
			}
		}
		if (copyData)
		{
			buf.put(array);
			buf.rewind();
			buf.limit(array.length);
		}
		return buf;
	}

	private static DoubleBuffer getNIOBuffer(double[] array, ThreadLocal<DoubleBuffer> threadLocal, boolean copyData)
	{
		if (array == null)
		{
			return null;
		}
		DoubleBuffer buf = threadLocal.get();
		if (buf == null)
		{
			buf = Buffers.newDirectDoubleBuffer(array.length);
			threadLocal.set(buf);
		}
		else
		{
			buf.rewind();
			if (buf.remaining() < array.length)
			{
				int newSize = Math.max(2 * buf.remaining(), array.length);
				buf = Buffers.newDirectDoubleBuffer(newSize);
				threadLocal.set(buf);
			}
		}
		if (copyData)
		{
			buf.put(array);
			buf.rewind();
			buf.limit(array.length);
		}
		return buf;
	}

	private static ByteBuffer getNIOBuffer(byte[] array, ThreadLocal<ByteBuffer> threadLocal, boolean copyData)
	{
		if (array == null)
		{
			return null;
		}
		ByteBuffer buf = threadLocal.get();
		if (buf == null)
		{
			buf = Buffers.newDirectByteBuffer(array.length);
			threadLocal.set(buf);
		}
		else
		{
			buf.rewind();
			if (buf.remaining() < array.length)
			{
				int newSize = Math.max(2 * buf.remaining(), array.length);
				buf = Buffers.newDirectByteBuffer(newSize);
				threadLocal.set(buf);
			}
		}
		if (copyData)
		{
			buf.put(array);
			buf.rewind();
			buf.limit(array.length);
		}
		return buf;
	}

	private static FloatBuffer[] getNIOBuffer(Object[] array, ThreadLocal<FloatBuffer[]> threadLocal)
	{
		if (array == null)
		{
			return null;
		}
		FloatBuffer[] bufs = threadLocal.get();

		// First resize array of FloatBuffers
		if (bufs == null)
		{
			bufs = new FloatBuffer[array.length];
			threadLocal.set(bufs);
		}
		else if (bufs.length < array.length)
		{
			FloatBuffer[] newBufs = new FloatBuffer[array.length];
			System.arraycopy(bufs, 0, newBufs, 0, bufs.length);
			bufs = newBufs;
			threadLocal.set(bufs);
		}

		// Now go down array of arrays, converting each into a direct FloatBuffer
		for (int i = 0; i < array.length; i++)
		{
			float[] cur = (float[]) array[i];
			FloatBuffer buf = bufs[i];
			if (buf == null)
			{
				buf = Buffers.newDirectFloatBuffer(cur.length);
				bufs[i] = buf;
			}
			else
			{
				buf.rewind();
				if (buf.remaining() < cur.length)
				{
					int newSize = Math.max(2 * buf.remaining(), cur.length);
					buf = Buffers.newDirectFloatBuffer(newSize);
					bufs[i] = buf;
				}
			}
			buf.put(cur);
			buf.rewind();
			buf.limit(cur.length);
		}

		return bufs;
	}

	// PJ: requesting caps is expensive, caps are unlikely to change during live
	// times, so we use a variable that is lazy initialized for each
	private isExtensionAvailable isExtensionAvailable = new isExtensionAvailable();

	private class isExtensionAvailable
	{
		private int GL_EXT_abgr = 0;

		private boolean GL_EXT_abgr(GL2ES2 gl)
		{
			if (GL_EXT_abgr == 0)
				GL_EXT_abgr = gl.isExtensionAvailable("GL_EXT_abgr") ? 1 : -1;

			return GL_EXT_abgr == 1;
		}

		private int GL_ARB_imaging = 0;

		private boolean GL_ARB_imaging(GL2ES2 gl)
		{
			if (GL_ARB_imaging == 0)
				GL_ARB_imaging = gl.isExtensionAvailable("GL_ARB_imaging") ? 1 : -1;

			return GL_ARB_imaging == 1;
		}

	}

	// Methods to get actual capabilities from Canvas3D
	@Override
	boolean hasDoubleBuffer(Canvas3D cv)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.hasDoubleBuffer()");
		if (VERBOSE)
			System.err.println("  Returning " + caps(cv).getDoubleBuffered());
		return caps(cv).getDoubleBuffered();
	}

	@Override
	boolean hasStereo(Canvas3D cv)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.hasStereo()");
		if (VERBOSE)
			System.err.println("  Returning " + caps(cv).getStereo());
		return caps(cv).getStereo();
	}

	@Override
	int getStencilSize(Canvas3D cv)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.getStencilSize()");
		if (VERBOSE)
			System.err.println("  Returning " + caps(cv).getStencilBits());
		return caps(cv).getStencilBits();
	}

	@Override
	boolean hasSceneAntialiasingMultisample(Canvas3D cv)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.hasSceneAntialiasingMultisample()");
		if (VERBOSE)
			System.err.println("  Returning " + caps(cv).getSampleBuffers());

		return caps(cv).getSampleBuffers();
	}

	@Override
	boolean hasSceneAntialiasingAccum(Canvas3D cv)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.hasSceneAntialiasingAccum()");
		// Accum style antialiasing is gone
		return false;
		/*GLCapabilities caps = caps(cv);
		if (VERBOSE)
			System.err
					.println("  Returning " + (caps.getAccumRedBits() > 0 && caps.getAccumGreenBits() > 0 && caps.getAccumBlueBits() > 0));
		return (caps.getAccumRedBits() > 0 && caps.getAccumGreenBits() > 0 && caps.getAccumBlueBits() > 0);
		*/

	}

	// Used to get caps for the canvas3d
	private static GLCapabilities caps(Canvas3D ctx)
	{
		if (ctx.drawable != null)
		{
			// latest state for on- and offscreen drawables
			return (GLCapabilities) drawable(ctx.drawable).getChosenGLCapabilities();
		}
		else
		{
			// state at the time of 'getBestConfiguration'
			return ((JoglGraphicsConfiguration) ctx.graphicsConfiguration).getGLCapabilities();
		}
	}

	// AWT AWT AWT AWT AWT AWT AWT AWT AWT
	// everything below here demands or is used by awt code
	// ---------------------------------------------------------------------

	// Determine whether specified graphics config is supported by pipeline
	@Override
	boolean isGraphicsConfigSupported(GraphicsConfigTemplate3D gct, GraphicsConfiguration gc)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.isGraphicsConfigSupported()");

		// FIXME: it looks like this method is implemented incorrectly
		// in the existing NativePipeline in both the Windows and X11
		// ports. According to the semantics of the javadoc, it looks
		// like this method is supposed to figure out the OpenGL
		// capabilities which would be requested by the passed
		// GraphicsConfiguration object were it to be used, and see
		// whether it is possible to create a context with them.
		// Instead, on both platforms, the implementations basically set
		// up a query based on the contents of the
		// GraphicsConfigTemplate3D object, using the
		// GraphicsConfiguration object only to figure out on which
		// GraphicsDevice and screen we're making the request, and see
		// whether it's possible to choose an OpenGL pixel format based
		// on that information. This makes this method less useful and
		// we can probably just safely return true here uniformly
		// without breaking anything.
		return true;
	}
	//
	// Canvas3D / GraphicsConfigTemplate3D methods - logic dealing with
	// native graphics configuration or drawing surface
	//

	// Return a graphics config based on the one passed in. Note that we can
	// assert that the input config is non-null and was created from a
	// GraphicsConfigTemplate3D.
	// This method must return a valid GraphicsConfig, or else it must throw
	// an exception if one cannot be returned.
	@Override
	GraphicsConfiguration getGraphicsConfig(GraphicsConfiguration gconfig)
	{

		if (VERBOSE)
			System.err.println("JoglPipeline.getGraphicsConfig()");

		GraphicsConfigInfo gcInf0 = Canvas3D.graphicsConfigTable.get(gconfig);
		AWTGraphicsConfiguration awtConfig = (AWTGraphicsConfiguration) gcInf0.getPrivateData();

		return awtConfig.getAWTGraphicsConfiguration();
	}

	private enum DisabledCaps
	{
		STEREO, AA, DOUBLE_BUFFER,
	}

	// Get best graphics config from pipeline
	@Override
	GraphicsConfiguration getBestConfiguration(GraphicsConfigTemplate3D gct, GraphicsConfiguration[] gc)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.getBestConfiguration()");

		// Create a GLCapabilities based on the GraphicsConfigTemplate3D
		final GLCapabilities caps = new GLCapabilities(profile);

		caps.setDoubleBuffered(gct.getDoubleBuffer() != GraphicsConfigTemplate.UNNECESSARY);

		caps.setStereo(gct.getStereo() != GraphicsConfigTemplate.UNNECESSARY);

		// Scene antialiasing only if double buffering
		if (gct.getSceneAntialiasing() != GraphicsConfigTemplate.UNNECESSARY && gct.getDoubleBuffer() != GraphicsConfigTemplate.UNNECESSARY)
		{
			caps.setSampleBuffers(true);
			int numSamples = MasterControl.getIntegerProperty("j3d.numSamples", 2);
			caps.setNumSamples(numSamples);
		}
		else
		{
			caps.setSampleBuffers(false);
			caps.setNumSamples(0);
		}

		caps.setDepthBits(gct.getDepthSize());
		caps.setStencilBits(gct.getStencilSize());

		caps.setRedBits(Math.max(5, gct.getRedSize()));
		caps.setGreenBits(Math.max(5, gct.getGreenSize()));
		caps.setBlueBits(Math.max(5, gct.getBlueSize()));

		// Issue 399: Request alpha buffer if transparentOffScreen is set
		if (VirtualUniverse.mc.transparentOffScreen)
		{
			caps.setAlphaBits(1);
		}

		// Add PREFERRED capabilities in order of least to highest priority and
		// we will try disabling them
		ArrayList<DisabledCaps> capsToDisable = new ArrayList<DisabledCaps>();

		if (gct.getStereo() == GraphicsConfigTemplate.PREFERRED)
		{
			capsToDisable.add(DisabledCaps.STEREO);
		}

		if (gct.getSceneAntialiasing() == GraphicsConfigTemplate.PREFERRED)
		{
			capsToDisable.add(DisabledCaps.AA);
		}

		// if AA is required, so is double buffering.
		if (gct.getSceneAntialiasing() != GraphicsConfigTemplate.REQUIRED && gct.getDoubleBuffer() == GraphicsConfigTemplate.PREFERRED)
		{
			capsToDisable.add(DisabledCaps.DOUBLE_BUFFER);
		}

		// Pick an arbitrary graphics device.
		GraphicsDevice device = gc[0].getDevice();
		AbstractGraphicsScreen screen = (device != null) ? AWTGraphicsScreen.createScreenDevice(device, AbstractGraphicsDevice.DEFAULT_UNIT)
				: AWTGraphicsScreen.createDefault();

		// Create a Frame and dummy GLCanvas to perform eager pixel format
		// selection

		// Note that we loop in similar fashion to the NativePipeline's
		// native code in the situation where we need to disable certain
		// capabilities which aren't required
		boolean tryAgain = true;
		CapabilitiesCapturer capturer = null;
		AWTGraphicsConfiguration awtConfig = null;
		while (tryAgain)
		{
			Frame f = new Frame();
			Dialog d = new Dialog(f);
			d.setUndecorated(true);
			d.setLayout(new BorderLayout());
			capturer = new CapabilitiesCapturer();
			try
			{
				awtConfig = createAwtGraphicsConfiguration(caps, capturer, screen);
				QueryCanvas canvas = new QueryCanvas(awtConfig, capturer);
				d.add(canvas, BorderLayout.CENTER);
				d.setSize(MIN_FRAME_SIZE, MIN_FRAME_SIZE);
				d.setVisible(true);
				canvas.doQuery();
				if (DEBUG_CONFIG)
				{
					System.err.println("Waiting for CapabilitiesCapturer");
				}
				// Try to wait for result without blocking EDT
				if (!EventQueue.isDispatchThread())
				{
					synchronized (capturer)
					{
						if (!capturer.done())
						{
							try
							{
								capturer.wait(WAIT_TIME);
							}
							catch (InterruptedException e)
							{
							}
						}
					}
				}
				disposeOnEDT(d);
				disposeOnEDT(f);
				tryAgain = false;
			}
			catch (GLException e)
			{
				// Failure to select a pixel format; try switching off one
				// of the only-preferred capabilities
				if (capsToDisable.size() == 0)
				{
					tryAgain = false;
				}
				else
				{
					switch (capsToDisable.remove(0))
					{
					case STEREO:
						caps.setStereo(false);
						break;
					case AA:
						caps.setSampleBuffers(false);
						break;
					case DOUBLE_BUFFER:
						caps.setDoubleBuffered(false);
						break;
					}
					awtConfig = null;
				}
			}
		}
		int chosenIndex = capturer.getChosenIndex();
		GLCapabilities chosenCaps = null;
		if (chosenIndex < 0)
		{
			if (DEBUG_CONFIG)
			{
				System.err.println("CapabilitiesCapturer returned invalid index");
			}
			// It's possible some platforms or implementations might not
			// support the GLCapabilitiesChooser mechanism; feed in the
			// same GLCapabilities later which we gave to the selector
			chosenCaps = caps;
		}
		else
		{
			if (DEBUG_CONFIG)
			{
				System.err.println("CapabilitiesCapturer returned index=" + chosenIndex);
			}
			chosenCaps = capturer.getCapabilities();
		}

		// FIXME chosenIndex isn't used anymore, used -1 instead of finding it.
		JoglGraphicsConfiguration config = new JoglGraphicsConfiguration(chosenCaps, chosenIndex, device);

		// FIXME: because of the fact that JoglGraphicsConfiguration
		// doesn't override hashCode() or equals(), we will basically be
		// creating a new one each time getBestConfiguration() is
		// called; in theory, we should probably map the same
		// GLCapabilities on the same GraphicsDevice to the same
		// JoglGraphicsConfiguration object

		// Cache the GraphicsTemplate3D
		GraphicsConfigInfo gcInf0 = new GraphicsConfigInfo(gct);
		gcInf0.setPrivateData(awtConfig);

		synchronized (Canvas3D.graphicsConfigTable)
		{
			Canvas3D.graphicsConfigTable.put(config, gcInf0);
		}

		return config;
	}

	@Override
	// Screen3D class calls during init and that init is only called in the init
	// of Canvas3D
	// See JoglPipeline for info on why this is dummied off
	int getScreen(final GraphicsDevice graphicsDevice)
	{
		if (VERBOSE)
			System.err.println("JoglPipeline.getScreen()");
		return 0;
	}

	// getBestConfiguration ONLY below here
	// VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
	// Non pipeline interface too

	// ----------------------------------------------------------------------
	// Helper classes and methods to support query context functionality
	// and pixel format selection
	// Used by Query Canvas apabilitiesCapturer and therefore only get best
	// configuration
	private interface ExtendedCapabilitiesChooser extends GLCapabilitiesChooser
	{
		public void init(GLContext context);
	}

	// Canvas subclass to help with various query operations such as the
	// "query context" mechanism and pixel format selection.
	// Must defeat and simplify the single-threading behavior of JOGL's
	// GLCanvas in order to be able to set up a temporary pixel format
	// and OpenGL context. Apparently simply turning off the
	// single-threaded mode isn't enough to do this.

	// Used by get best configuration
	private final class QueryCanvas extends Canvas
	{

		private GLDrawable glDrawable;
		private ExtendedCapabilitiesChooser chooser;
		private boolean alreadyRan;

		private AWTGraphicsConfiguration awtConfig = null;
		private JAWTWindow nativeWindow = null;

		private QueryCanvas(AWTGraphicsConfiguration awtConfig, ExtendedCapabilitiesChooser chooser)
		{
			// The platform-specific GLDrawableFactory will only provide a
			// non-null GraphicsConfiguration on platforms where this is
			// necessary (currently only X11, as Windows allows the pixel
			// format of the window to be set later and Mac OS X seems to
			// handle this very differently than all other platforms). On
			// other platforms this method returns null; it is the case (at
			// least in the Sun AWT implementation) that this will result in
			// equivalent behavior to calling the no-arg super() constructor
			// for Canvas.
			super(awtConfig.getAWTGraphicsConfiguration());

			this.awtConfig = awtConfig;
			this.chooser = chooser;
		}

		@Override
		public void addNotify()
		{
			super.addNotify();

			nativeWindow = (JAWTWindow) NativeWindowFactory.getNativeWindow(this, awtConfig);
			nativeWindow.lockSurface();
			try
			{
				glDrawable = GLDrawableFactory.getFactory(profile).createGLDrawable(nativeWindow);
			}
			finally
			{
				nativeWindow.unlockSurface();
			}

			glDrawable.setRealized(true);
		}

		// It seems that at least on Mac OS X we need to do the OpenGL
		// context-related work outside of the addNotify call because the
		// Canvas hasn't been resized to a non-zero size by that point
		private void doQuery()
		{
			if (alreadyRan)
				return;
			GLContext context = glDrawable.createContext(null);
			int res = context.makeCurrent();
			if (res != GLContext.CONTEXT_NOT_CURRENT)
			{
				try
				{
					chooser.init(context);
				}
				finally
				{
					context.release();
				}
			}
			context.destroy();
			alreadyRan = true;

			glDrawable.setRealized(false);
			nativeWindow.destroy();
		}
	}

	// Used by get best configuration
	private static AWTGraphicsConfiguration createAwtGraphicsConfiguration(GLCapabilities capabilities, CapabilitiesChooser chooser,
			AbstractGraphicsScreen screen)
	{
		GraphicsConfigurationFactory factory = GraphicsConfigurationFactory.getFactory(AWTGraphicsDevice.class, GLCapabilities.class);
		AWTGraphicsConfiguration awtGraphicsConfiguration = (AWTGraphicsConfiguration) factory.chooseGraphicsConfiguration(capabilities,
				capabilities, chooser, screen, VisualIDHolder.VID_UNDEFINED);
		return awtGraphicsConfiguration;
	}

	// Used in conjunction with IndexCapabilitiesChooser in pixel format
	// selection -- see getBestConfiguration

	// Used by getBestConfiguration
	private static class CapabilitiesCapturer extends DefaultGLCapabilitiesChooser implements ExtendedCapabilitiesChooser
	{
		private boolean done;
		private GLCapabilities capabilities;
		private int chosenIndex = -1;

		public boolean done()
		{
			return done;
		}

		public GLCapabilities getCapabilities()
		{
			return capabilities;
		}

		public int getChosenIndex()
		{
			return chosenIndex;
		}

		public int chooseCapabilities(GLCapabilities desired, GLCapabilities[] available, int windowSystemRecommendedChoice)
		{
			int res = super.chooseCapabilities(desired, Arrays.asList(available), windowSystemRecommendedChoice);
			capabilities = available[res];
			chosenIndex = res;
			markDone();
			return res;
		}

		@Override
		public void init(GLContext context)
		{
			// Avoid hanging things up for several seconds
			kick();
		}

		private void markDone()
		{
			synchronized (this)
			{
				done = true;
				notifyAll();
			}
		}

		private void kick()
		{
			synchronized (this)
			{
				notifyAll();
			}
		}
	}

	// Used to support the query context mechanism -- needs to be more
	// than just a GLCapabilitiesChooser

	// ONLY used by createQuerycontext above, hence unused
	// What possibly invoked via some sort of crazy reflect, do not delete
	// can't seem to get it invoked now?
	private final class ContextQuerier extends DefaultGLCapabilitiesChooser implements ExtendedCapabilitiesChooser
	{
		private Canvas3D canvas;
		private boolean done;

		public ContextQuerier(Canvas3D canvas)
		{
			this.canvas = canvas;
		}

		public boolean done()
		{
			return done;
		}

		@Override
		public void init(GLContext context)
		{
			// This is basically a temporary, NOTE not JoglesContext either
			JoglContext jctx = new JoglContext(context);
			GL2ES2 gl = context.getGL().getGL2ES2();
			// Set up various properties
			if (getPropertiesFromCurrentContext(jctx, gl))
			{
				setupCanvasProperties(canvas, jctx, gl);
			}
			markDone();
		}

		private void markDone()
		{
			synchronized (this)
			{
				done = true;
				notifyAll();
			}
		}
	}

	// used by getBestConfiguration above
	private static void disposeOnEDT(final Window f)
	{
		Runnable r = new Runnable() {
			@Override
			public void run()
			{
				f.setVisible(false);
				f.dispose();
			}
		};
		if (!EventQueue.isDispatchThread())
		{
			EventQueue.invokeLater(r);
		}
		else
		{
			r.run();
		}
	}

}
