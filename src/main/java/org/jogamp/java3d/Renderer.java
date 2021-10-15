/*
 * Copyright 1997-2008 Sun Microsystems, Inc. All Rights Reserved. DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE
 * HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it under the terms of the GNU General Public
 * License version 2 only, as published by the Free Software Foundation. Sun designates this particular file as subject
 * to the "Classpath" exception as provided by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License version 2 for
 * more details (a copy is included in the LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version 2 along with this work; if not, write to
 * the Free Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara, CA 95054 USA or visit www.sun.com if you
 * need additional information or have any questions.
 *
 */

/*
 * Portions of this code were derived from work done by the Blackdown group (www.blackdown.org), who did the initial
 * Linux implementation of the Java 3D API.
 */

package org.jogamp.java3d;

import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;

import org.jogamp.vecmath.Point3d;
import org.jogamp.vecmath.Vector3d;
import org.jogamp.vecmath.Vector3f;

class Renderer extends J3dThread {

	// The following are DecalGroup rendering states
	static final int				DECAL_NONE					= 0;
	static final int				DECAL_1ST_CHILD				= 1;
	static final int				DECAL_NTH_CHILD				= 2;

	// stuff for scene antialiasing
	//	static final int				NUM_ACCUMULATION_SAMPLES	= 8;

	//	static final float				ACCUM_SAMPLES_X[]			= {-0.54818f, 0.56438f, 0.39462f, -0.54498f, -0.83790f,
	//		-0.39263f, 0.32254f, 0.84216f};

	//	static final float				ACCUM_SAMPLES_Y[]			= {0.55331f, -0.53495f, 0.41540f, -0.52829f, 0.82102f,
	//		-0.27383f, 0.09133f, -0.84399f};

	//	static final float				accumValue					= 1.0f / NUM_ACCUMULATION_SAMPLES;

	// The following are Render arguments
	static final int				RENDER						= 0;
	static final int				SWAP						= 1;
	static final int				REQUESTRENDER				= 2;
	static final int				REQUESTCLEANUP				= 3;

	// Renderer Structure used for the messaging to the renderer
	RendererStructure				rendererStructure			= new RendererStructure();

	// vworldtoVpc matrix for background geometry
	Transform3D						bgVworldToVpc				= new Transform3D();

	private static int				numInstances				= 0;
	private int						instanceNum					= -1;

	// Local copy of sharedStereZBuffer flag
	boolean							sharedStereoZBuffer;

	// This is the id for the underlying sharable graphics context
	Context							sharedCtx					= null;

	// since the sharedCtx id can be the same as the previous one,
	// we need to keep a time stamp to differentiate the contexts with the
	// same id
	long							sharedCtxTimeStamp			= 0;

	// display and drawable, used to free shared context
	private Drawable				sharedCtxDrawable			= null;

	/**
	 * This is the id of the current rendering context
	 */
	Context							currentCtx					= null;

	/**
	 * This is the id of the current rendering drawable
	 */
	Drawable						currentDrawable				= null;

	// an unique bit to identify this renderer
	int								rendererBit					= 0;
	// an unique number to identify this renderer : ( rendererBit = 1 << rendererId)
	int								rendererId					= 0;

	// List of renderMolecules that are dirty due to additions
	// or removal of renderAtoms from their display list set
	// of renderAtoms
	ArrayList<RenderMolecule>		dirtyRenderMoleculeList		= new ArrayList<RenderMolecule>();

	// List of individual dlists that need to be rebuilt
	ArrayList<RenderAtomListInfo>	dirtyRenderAtomList			= new ArrayList<RenderAtomListInfo>();

	// List of (Rm, rInfo) pair of individual dlists that need to be rebuilt
	ArrayList<Object[]>				dirtyDlistPerRinfoList		= new ArrayList<Object[]>();

	// Texture and display list that should be freed
	ArrayList<Integer>				textureIdResourceFreeList	= new ArrayList<Integer>();
	ArrayList<Integer>				displayListResourceFreeList	= new ArrayList<Integer>();

	// Texture that should be reload
	ArrayList<TextureRetained>		textureReloadList			= new ArrayList<TextureRetained>();

	J3dMessage[]					renderMessage;

	// The screen for this Renderer. Note that this renderer may share
	// by both on screen and off screen. When view unregister, we need
	// to set both reference to null.
	Screen3D						onScreen;
	Screen3D						offScreen;

	// full screen anti-aliasing projection matrices
	//	Transform3D						accumLeftProj				= new Transform3D();
	//	Transform3D						accumRightProj				= new Transform3D();
	//	Transform3D						accumInfLeftProj			= new Transform3D();
	//	Transform3D						accumInfRightProj			= new Transform3D();

	// rendering messages
	J3dMessage						m[];
	int								nmesg						= 0;

	// List of contexts created
	ArrayList<Context>				listOfCtxs					= new ArrayList<Context>();

	// Parallel list of canvases
	ArrayList<Canvas3D>				listOfCanvases				= new ArrayList<Canvas3D>();

	boolean							needToRebuildDisplayList	= false;

	// True when either one of dirtyRenderMoleculeList,
	// dirtyDlistPerRinfoList, dirtyRenderAtomList size > 0
	boolean							dirtyDisplayList			= false;

	// Remember OGL context resources to free
	// before context is destroy.
	// It is used when sharedCtx = true;
	ArrayList<TextureRetained>		textureIDResourceTable		= new ArrayList<TextureRetained>(5);

	// Instrumentation of Java 3D renderer
	private long					lastSwapTime				= System.nanoTime();

	private synchronized int newInstanceNum() {
		return (++numInstances);
	}

	@Override
	int getInstanceNum() {
		if (instanceNum == -1)
			instanceNum = newInstanceNum();
		return instanceNum;
	}

	/**
	 * Constructs a new Renderer
	 */
	Renderer(ThreadGroup t) {
		super(t);
		setName("J3D-Renderer-" + getInstanceNum());

		type = J3dThread.RENDER_THREAD;
		rendererId = VirtualUniverse.mc.getRendererId();
		rendererBit = (1 << rendererId);
		renderMessage = new J3dMessage[1];
	}

	/**
	 * The main loop for the renderer.
	 */
	@Override
	void doWork(long referenceTime) {
		RenderBin renderBin = null;
		Canvas3D cv, canvas = null;
		Object firstArg;
		View view = null;
		int stereo_mode;
		int num_stereo_passes = 1;//, num_accum_passes = 1;
		int pass, i, j;
		//boolean doAccum = false;
		//double accumDx = 0.0f, accumDy = 0.0f;
		//double accumDxFactor = 1.0f, accumDyFactor = 1.0f;

		//double accumLeftX = 0.0, accumLeftY = 0.0, accumRightX = 0.0, accumRightY = 0.0, accumInfLeftX = 0.0,
		//		accumInfLeftY = 0.0, accumInfRightX = 0.0, accumInfRightY = 0.0;
		int opArg;
		Transform3D t3d = null;

		opArg = ((Integer)args [0]).intValue();

		try {
			if (opArg == SWAP) {

				Object[] swapArray = (Object[])args [2];

				view = (View)args [3];

				for (i = 0; i < swapArray.length; i++) {
					cv = (Canvas3D)swapArray [i];
					if (!cv.isRunning) {
						continue;
					}

					doneSwap: try {

						if (!cv.validCanvas) {
							continue;
						}

						if (cv.active && (cv.ctx != null) && (cv.view != null) && (cv.imageReady)) {
							// don't swap double buffered AuoOffScreenCanvas3D/JCanvas3D
							// manual offscreen rendering doesn't pass this code (opArg == SWAP)
							if (cv.useDoubleBuffer && !cv.offScreen) {
								synchronized (cv.drawingSurfaceObject) {
									if (cv.validCtx) {
										if (VirtualUniverse.mc.doDsiRenderLock) {
											// Set doDsiLock flag for rendering based on system
											// property,  If we force DSI lock for swap
											// buffer,  we lose most of the parallelism that having
											// multiple renderers gives us.

											if (!cv.drawingSurfaceObject.renderLock()) {
												break doneSwap;
											}
											cv.makeCtxCurrent();
											cv.syncRender(cv.ctx, true);
											cv.swapBuffers(cv.ctx, cv.drawable);
											cv.drawingSurfaceObject.unLock();
										} else {
											cv.makeCtxCurrent();
											cv.syncRender(cv.ctx, true);
											cv.swapBuffers(cv.ctx, cv.drawable);
										}
									}
								}
							}
							cv.view.inCanvasCallback = true;
							try {
								cv.postSwap();
							} catch (RuntimeException e) {
								System.err.println("Exception occurred during Canvas3D callback:");
								e.printStackTrace();
							} catch (Error e) {
								// Issue 264 - catch Error so Renderer doesn't die
								System.err.println("Error occurred during Canvas3D callback:");
								e.printStackTrace();
							}
							// reset flag
							cv.imageReady = false;
							cv.view.inCanvasCallback = false;
							// Clear canvasDirty bit ONLY when postSwap() success

							if (MasterControl.isStatsLoggable(Level.INFO)) {
								// Instrumentation of Java 3D renderer
								long currSwapTime = System.nanoTime();
								long deltaTime = currSwapTime - lastSwapTime;
								lastSwapTime = currSwapTime;
								VirtualUniverse.mc.recordTime(MasterControl.TimeType.TOTAL_FRAME, deltaTime);
							}

							// Set all dirty bits except environment set and lightbin
							// they are only set dirty if the last used light bin or
							// environment set values for this canvas change between
							// one frame and other

							if (!cv.ctxChanged) {
								cv.canvasDirty = (0xffff & ~(Canvas3D.LIGHTBIN_DIRTY | Canvas3D.LIGHTENABLES_DIRTY
																| Canvas3D.AMBIENTLIGHT_DIRTY | Canvas3D.MODELCLIP_DIRTY
																| Canvas3D.VIEW_MATRIX_DIRTY | Canvas3D.FOG_DIRTY));
								// Force reload of transform next frame
								cv.modelMatrix = null;

								// Force the cached renderAtom to null
								cv.ra = null;
							} else {
								cv.ctxChanged = false;
							}
						}
					} catch (NullPointerException ne) {
						// Ignore NPE
						if (VirtualUniverse.mc.doDsiRenderLock) {
							cv.drawingSurfaceObject.unLock();
						}
					} catch (RuntimeException ex) {
						ex.printStackTrace();

						if (VirtualUniverse.mc.doDsiRenderLock) {
							cv.drawingSurfaceObject.unLock();
						}

						// Issue 260 : indicate fatal error and notify error listeners
						cv.setFatalError();
						RenderingError err = new RenderingError(RenderingError.UNEXPECTED_RENDERING_ERROR,
								J3dI18N.getString("Renderer0"));
						err.setCanvas3D(cv);
						err.setGraphicsDevice(cv.graphicsConfiguration.getDevice());
						notifyErrorListeners(err);
					}

					cv.releaseCtx();
				}

				if (view != null) { // STOP_TIMER
					// incElapsedFrames() is delay until MC:updateMirroObject
					if (view.viewCache.getDoHeadTracking()) {
						VirtualUniverse.mc.sendRunMessage(view, J3dThread.RENDER_THREAD);
					}
				}

			} else if (opArg == REQUESTCLEANUP) {
				Integer mtype = (Integer)args [2];

				if (mtype == MasterControl.REMOVEALLCTXS_CLEANUP) {
					// from MasterControl when View is last views
					removeAllCtxs();
				} else if (mtype == MasterControl.FREECONTEXT_CLEANUP) {
					// from MasterControl freeContext(View v)
					cv = (Canvas3D)args [1];
					removeCtx(cv, cv.drawable, cv.ctx, true, true, false);
				} else if (mtype == MasterControl.RESETCANVAS_CLEANUP) {
					// from MasterControl RESET_CANVAS postRequest
					cv = (Canvas3D)args [1];
					if (cv.ctx != null) {
						cv.makeCtxCurrent();
					}
					cv.freeContextResources(cv.screen.renderer, true, cv.ctx);
				} else if (mtype == MasterControl.REMOVECTX_CLEANUP) {
					// from Canvas3D removeCtx() postRequest
					Object[] obj = (Object[])args [1];
					Canvas3D c = (Canvas3D)obj [0];
					removeCtx(c, (Drawable)obj [2], (Context)obj [3], false, !c.offScreen, false);
				}
				return;
			} else { // RENDER || REQUESTRENDER

				int renderType;
				nmesg = 0;
				int totalMessages = 0;
				if (opArg == RENDER) {
					m = renderMessage;
					m [0] = new J3dMessage();
					// Issue 131: Set appropriate message type
					if (((Canvas3D)args [1]).offScreen) {
						m [0].type = J3dMessage.RENDER_OFFSCREEN;
					} else {
						m [0].type = J3dMessage.RENDER_RETAINED;
					}
					m [0].incRefcount();
					m [0].args [0] = args [1];
					totalMessages = 1;
				} else { // REQUESTRENDER
					m = rendererStructure.getMessages();
					totalMessages = rendererStructure.getNumMessage();
					if (totalMessages <= 0) {
						return;
					}
				}

				doneRender: while (nmesg < totalMessages) {

					firstArg = m [nmesg].args [0];

					if (firstArg == null) {
						Object secondArg = m [nmesg].args [1];
						if (secondArg instanceof Canvas3D) {
							// message from Canvas3Ds to destroy Context
							Integer reqType = (Integer)m [nmesg].args [2];
							Canvas3D c = (Canvas3D)secondArg;
							if (reqType == MasterControl.SET_GRAPHICSCONFIG_FEATURES) {
								try {
									if (c.offScreen) {
										// NEW : offscreen supports double buffering
										c.doubleBufferAvailable = c.hasDoubleBuffer(); // was : false
										// offScreen canvas doesn't supports stereo
										c.stereoAvailable = false;
									} else {
										c.doubleBufferAvailable = c.hasDoubleBuffer();
										c.stereoAvailable = c.hasStereo();
									}

									// Setup stencil related variables.
									c.actualStencilSize = c.getStencilSize();
									boolean userOwnsStencil = c.requestedStencilSize > 0;

									c.userStencilAvailable = (userOwnsStencil && (c.actualStencilSize > 0));
									c.systemStencilAvailable = (!userOwnsStencil && (c.actualStencilSize > 0));

									//c.sceneAntialiasingMultiSamplesAvailable = c.hasSceneAntialiasingMultisample();

									//if (c.sceneAntialiasingMultiSamplesAvailable) {
									//	c.sceneAntialiasingAvailable = true;
									//} else {
									//	c.sceneAntialiasingAvailable = c.hasSceneAntialiasingAccum();
									//}
								} catch (RuntimeException ex) {
									ex.printStackTrace();

									// Issue 260 : indicate fatal error and notify error listeners
									c.setFatalError();
									RenderingError err = new RenderingError(RenderingError.GRAPHICS_CONFIG_ERROR,
											J3dI18N.getString("Renderer1"));
									err.setCanvas3D(c);
									err.setGraphicsDevice(c.graphicsConfiguration.getDevice());
									notifyErrorListeners(err);
								}
								GraphicsConfigTemplate3D.runMonitor(J3dThread.NOTIFY);
							} else if (reqType == MasterControl.SET_QUERYPROPERTIES) {
								try {
									c.createQueryContext();
								} catch (RuntimeException ex) {
									ex.printStackTrace();

									// Issue 260 : indicate fatal error and notify error listeners
									c.setFatalError();
									RenderingError err = new RenderingError(RenderingError.CONTEXT_CREATION_ERROR,
											J3dI18N.getString("Renderer2"));
									err.setCanvas3D(c);
									err.setGraphicsDevice(c.graphicsConfiguration.getDevice());
									notifyErrorListeners(err);
								}
								// currentCtx change after we create a new context
								GraphicsConfigTemplate3D.runMonitor(J3dThread.NOTIFY);
								currentCtx = null;
								currentDrawable = null;
							}
						} else if (secondArg instanceof Integer) {
							// Issue 121 - This was formerly used as a message from
							// the now-nonexistant TextureRetained finalize() method
							// to free the texture id
							throw new AssertionError();
						} else if (secondArg instanceof GeometryArrayRetained) {
							// message from GeometryArrayRetained
							// clearLive() to free D3D array
							//((GeometryArrayRetained) secondArg).freeD3DArray(false);
						} else if (secondArg instanceof GraphicsConfigTemplate3D) {
							GraphicsConfigTemplate3D gct = (GraphicsConfigTemplate3D)secondArg;
							Integer reqType = (Integer)m [nmesg].args [2];
							if (reqType == MasterControl.GETBESTCONFIG) {
								GraphicsConfiguration gcfg = null;
								GraphicsConfiguration[] gcList = (GraphicsConfiguration[])gct.testCfg;
								try {
									gcfg = Pipeline.getPipeline().getBestConfiguration(gct, gcList);
								} catch (NullPointerException npe) {
									npe.printStackTrace();
								} catch (RuntimeException ex) {
									ex.printStackTrace();

									// Issue 260 : notify error listeners
									RenderingError err = new RenderingError(RenderingError.GRAPHICS_CONFIG_ERROR,
											J3dI18N.getString("Renderer3"));
									err.setGraphicsDevice(gcList [0].getDevice());
									notifyErrorListeners(err);
								}

								gct.testCfg = gcfg;
							} else if (reqType == MasterControl.ISCONFIGSUPPORT) {
								boolean rval = false;
								GraphicsConfiguration gc = (GraphicsConfiguration)gct.testCfg;
								try {
									if (Pipeline.getPipeline().isGraphicsConfigSupported(gct, gc)) {
										rval = true;
									}
								} catch (NullPointerException npe) {
									npe.printStackTrace();
								} catch (RuntimeException ex) {
									ex.printStackTrace();

									// Issue 260 : notify error listeners
									RenderingError err = new RenderingError(RenderingError.GRAPHICS_CONFIG_ERROR,
											J3dI18N.getString("Renderer4"));
									err.setGraphicsDevice(gc.getDevice());
									notifyErrorListeners(err);
								}

								gct.testCfg = Boolean.valueOf(rval);
							}
							GraphicsConfigTemplate3D.runMonitor(J3dThread.NOTIFY);
						}

						m [nmesg++].decRefcount();
						continue;
					}

					canvas = (Canvas3D)firstArg;

					renderType = m [nmesg].type;

					if (renderType == J3dMessage.CREATE_OFFSCREENBUFFER) {
						// Fix for issue 18.
						// Fix for issue 20.

						canvas.drawable = null;
						try {
							// Issue 396. Pass in a null ctx for 2 reasons :
							//   1) We should not use ctx field directly without buffering in a msg.
							//   2) canvas.ctx should be null.
							canvas.drawable = canvas.createOffScreenBuffer(null, canvas.offScreenCanvasSize.width,
									canvas.offScreenCanvasSize.height);
						} catch (RuntimeException ex) {
							ex.printStackTrace();
						}

						if (canvas.drawable == null) {
							// Issue 260 : indicate fatal error and notify error listeners
							canvas.setFatalError();
							RenderingError err = new RenderingError(RenderingError.OFF_SCREEN_BUFFER_ERROR,
									J3dI18N.getString("Renderer5"));
							err.setCanvas3D(canvas);
							err.setGraphicsDevice(canvas.graphicsConfiguration.getDevice());
							notifyErrorListeners(err);
						}

						canvas.offScreenBufferPending = false;
						m [nmesg++].decRefcount();
						continue;
					} else if (renderType == J3dMessage.DESTROY_CTX_AND_OFFSCREENBUFFER) {
						Object[] obj = m [nmesg].args;

						// Fix for issue 175: destroy ctx & off-screen buffer
						// Fix for issue 340: get display, drawable & ctx from msg
						removeCtx(canvas, (Drawable)obj [2], (Context)obj [3], false, !canvas.offScreen, true);

						canvas.offScreenBufferPending = false;
						m [nmesg++].decRefcount();
						continue;
					} else if (renderType == J3dMessage.ALLOCATE_CANVASID) {
						canvas.allocateCanvasId();
					} else if (renderType == J3dMessage.FREE_CANVASID) {
						canvas.freeCanvasId();
					}

					if ((canvas.view == null) || !canvas.firstPaintCalled) {
						// This happen when the canvas just remove from the View
						if (renderType == J3dMessage.RENDER_OFFSCREEN) {
							canvas.offScreenRendering = false;
						}
						m [nmesg++].decRefcount();
						continue;
					}

					if (!canvas.validCanvas && (renderType != J3dMessage.RENDER_OFFSCREEN)) {
						m [nmesg++].decRefcount();
						continue;
					}

					if (renderType == J3dMessage.RESIZE_CANVAS) {
						// render the image again after resize
						VirtualUniverse.mc.sendRunMessage(canvas.view, J3dThread.RENDER_THREAD);
						m [nmesg++].decRefcount();
					} else if (renderType == J3dMessage.TOGGLE_CANVAS) {
						VirtualUniverse.mc.sendRunMessage(canvas.view, J3dThread.RENDER_THREAD);
						m [nmesg++].decRefcount();
					} else if (renderType == J3dMessage.RENDER_IMMEDIATE) {
						int command = ((Integer)m [nmesg].args [1]).intValue();
						//System.err.println("command= " + command);
						if (canvas.isFatalError()) {
							continue;
						}
						if (canvas.ctx == null) {
							synchronized (VirtualUniverse.mc.contextCreationLock) {
								canvas.ctx = canvas.createNewContext(null, false);

								if (canvas.ctx == null) {
									canvas.drawingSurfaceObject.unLock();
									// Issue 260 : indicate fatal error and notify error listeners
									canvas.setFatalError();
									RenderingError err = new RenderingError(RenderingError.CONTEXT_CREATION_ERROR,
											J3dI18N.getString("Renderer7"));
									err.setCanvas3D(canvas);
									err.setGraphicsDevice(canvas.graphicsConfiguration.getDevice());
									notifyErrorListeners(err);

									break doneRender;
								}
								// createNewContext finishes with a release, re-make current so the init calls below work
								canvas.makeCtxCurrent();

								if (canvas.graphics2D != null) {
									canvas.graphics2D.init();
								}

								canvas.ctxTimeStamp = VirtualUniverse.mc.getContextTimeStamp();
								canvas.screen.renderer.listOfCtxs.add(canvas.ctx);
								canvas.screen.renderer.listOfCanvases.add(canvas);

								// enable separate specular color
								canvas.enableSeparateSpecularColor();
							}

							// create the cache texture state in canvas
							// for state download checking purpose
							if (canvas.texUnitState == null) {
								canvas.createTexUnitState();
							}

							canvas.drawingSurfaceObject.contextValidated();
							canvas.screen.renderer.currentCtx = canvas.ctx;
							canvas.screen.renderer.currentDrawable = canvas.drawable;
							canvas.graphicsContext3D.initializeState();
							canvas.ctxChanged = true;
							canvas.canvasDirty = 0xffff;
							// Update Appearance
							canvas.graphicsContext3D.updateState(canvas.view.renderBin, RenderMolecule.SURFACE);

							canvas.currentLights = new LightRetained[canvas.getNumCtxLights(canvas.ctx)];

							for (j = 0; j < canvas.currentLights.length; j++) {
								canvas.currentLights [j] = null;
							}
						}

						canvas.makeCtxCurrent();
						try {

							switch (command) {
								case GraphicsContext3D.CLEAR:
									canvas.graphicsContext3D.doClear();
									break;
								case GraphicsContext3D.DRAW:
									canvas.graphicsContext3D.doDraw((Geometry)m [nmesg].args [2]);
									break;
								case GraphicsContext3D.SWAP:
									canvas.doSwap();
									break;
								case GraphicsContext3D.READ_RASTER:
									canvas.graphicsContext3D.doReadRaster((Raster)m [nmesg].args [2],
											(CountDownLatch)m [nmesg].args [3]);
									break;
								case GraphicsContext3D.SET_APPEARANCE:
									canvas.graphicsContext3D.doSetAppearance((Appearance)m [nmesg].args [2]);
									break;
								case GraphicsContext3D.SET_BACKGROUND:
									canvas.graphicsContext3D.doSetBackground((Background)m [nmesg].args [2]);
									break;
								case GraphicsContext3D.SET_FOG:
									canvas.graphicsContext3D.doSetFog((Fog)m [nmesg].args [2]);
									break;
								case GraphicsContext3D.SET_LIGHT:
									canvas.graphicsContext3D.doSetLight((Light)m [nmesg].args [2],
											((Integer)m [nmesg].args [3]).intValue());
									break;
								case GraphicsContext3D.INSERT_LIGHT:
									canvas.graphicsContext3D.doInsertLight((Light)m [nmesg].args [2],
											((Integer)m [nmesg].args [3]).intValue());
									break;
								case GraphicsContext3D.REMOVE_LIGHT:
									canvas.graphicsContext3D.doRemoveLight(((Integer)m [nmesg].args [2]).intValue());
									break;
								case GraphicsContext3D.ADD_LIGHT:
									canvas.graphicsContext3D.doAddLight((Light)m [nmesg].args [2]);
									break;
								case GraphicsContext3D.SET_HI_RES:
									canvas.graphicsContext3D.doSetHiRes((HiResCoord)m [nmesg].args [2]);
									break;
								case GraphicsContext3D.SET_MODEL_TRANSFORM:
									t3d = (Transform3D)m [nmesg].args [2];
									canvas.graphicsContext3D.doSetModelTransform(t3d);
									break;
								case GraphicsContext3D.MULTIPLY_MODEL_TRANSFORM:
									t3d = (Transform3D)m [nmesg].args [2];
									canvas.graphicsContext3D.doMultiplyModelTransform(t3d);
									break;
								case GraphicsContext3D.SET_SOUND:
									canvas.graphicsContext3D.doSetSound((Sound)m [nmesg].args [2],
											((Integer)m [nmesg].args [3]).intValue());
									break;
								case GraphicsContext3D.INSERT_SOUND:
									canvas.graphicsContext3D.doInsertSound((Sound)m [nmesg].args [2],
											((Integer)m [nmesg].args [3]).intValue());
									break;
								case GraphicsContext3D.REMOVE_SOUND:
									canvas.graphicsContext3D.doRemoveSound(((Integer)m [nmesg].args [2]).intValue());
									break;
								case GraphicsContext3D.ADD_SOUND:
									canvas.graphicsContext3D.doAddSound((Sound)m [nmesg].args [2]);
									break;
								case GraphicsContext3D.SET_AURAL_ATTRIBUTES:
									canvas.graphicsContext3D.doSetAuralAttributes((AuralAttributes)m [nmesg].args [2]);
									break;
								case GraphicsContext3D.SET_BUFFER_OVERRIDE:
									canvas.graphicsContext3D
											.doSetBufferOverride(((Boolean)m [nmesg].args [2]).booleanValue());
									break;
								case GraphicsContext3D.SET_FRONT_BUFFER_RENDERING:
									canvas.graphicsContext3D
											.doSetFrontBufferRendering(((Boolean)m [nmesg].args [2]).booleanValue());
									break;
								case GraphicsContext3D.SET_STEREO_MODE:
									canvas.graphicsContext3D.doSetStereoMode(((Integer)m [nmesg].args [2]).intValue());
									break;
								case GraphicsContext3D.FLUSH:
									canvas.graphicsContext3D.doFlush(((Boolean)m [nmesg].args [2]).booleanValue());
									break;
								case GraphicsContext3D.FLUSH2D:
									canvas.graphics2D.doFlush();
									break;
								case GraphicsContext3D.DRAWANDFLUSH2D:
									Object ar[] = m [nmesg].args;
									canvas.graphics2D.doDrawAndFlushImage((BufferedImage)ar [2], ((Point)ar [3]).x,
											((Point)ar [3]).y, (ImageObserver)ar [4]);
									break;
								case GraphicsContext3D.DISPOSE2D:
									// Issue 583 - the graphics2D field may be null here
									if (canvas.graphics2D != null) {
										canvas.graphics2D.doDispose();
									}
									break;
								case GraphicsContext3D.SET_MODELCLIP:
									canvas.graphicsContext3D.doSetModelClip((ModelClip)m [nmesg].args [2]);
									break;
								default:
									break;
							}

						} catch (RuntimeException ex) {
							ex.printStackTrace();

							// Issue 260 : indicate fatal error and notify error listeners
							canvas.setFatalError();
							RenderingError err = new RenderingError(RenderingError.CONTEXT_CREATION_ERROR,
									J3dI18N.getString("Renderer6"));
							err.setCanvas3D(canvas);
							err.setGraphicsDevice(canvas.graphicsConfiguration.getDevice());
							notifyErrorListeners(err);
						}

						m [nmesg++].decRefcount();
						canvas.releaseCtx();
					} else { // retained mode rendering
						long startRenderTime = 0L;
						if (MasterControl.isStatsLoggable(Level.INFO)) {
							// Instrumentation of Java 3D renderer
							startRenderTime = System.nanoTime();
						}

						m [nmesg++].decRefcount();

						if (canvas.isFatalError()) {
							continue;
						}

						ImageComponent2DRetained offBufRetained = null;

						if (renderType == J3dMessage.RENDER_OFFSCREEN) {
							// Issue 131: set offScreenRendering flag here, since it
							// otherwise won't be set for auto-off-screen rendering
							// (which doesn't use renderOffScreenBuffer)
							canvas.offScreenRendering = true;
							if (canvas.drawable == null || !canvas.active) {
								canvas.offScreenRendering = false;
								continue;
							} else {
								offBufRetained = (ImageComponent2DRetained)canvas.offScreenBuffer.retained;

								if (offBufRetained.isByReference()) {
									offBufRetained.geomLock.getLock();
								}

								offBufRetained.evaluateExtensions(canvas);

							}

						} else if (!canvas.active) {
							continue;
						}

						// Issue 78 - need to get the drawingSurface info every
						// frame; this is necessary since the HDC (window ID)
						// on Windows can become invalidated without our
						// being notified!
						if (!canvas.offScreen) {
							canvas.drawingSurfaceObject.getDrawingSurfaceObjectInfo();
						}

						renderBin = canvas.view.renderBin;

						// setup rendering context

						// We need to catch NullPointerException when the dsi
						// gets yanked from us during a remove.

						if (canvas.useSharedCtx) {

							if (sharedCtx == null) {
								sharedCtxDrawable = canvas.drawable;

								// Always lock for context create
								if (!canvas.drawingSurfaceObject.renderLock()) {
									if ((offBufRetained != null) && offBufRetained.isByReference()) {
										offBufRetained.geomLock.unLock();
									}
									canvas.offScreenRendering = false;
									break doneRender;
								}

								synchronized (VirtualUniverse.mc.contextCreationLock) {
									sharedCtx = null;
									try {
										sharedCtx = canvas.createNewContext(null, true);
									} catch (RuntimeException ex) {
										ex.printStackTrace();
									}

									if (sharedCtx == null) {
										canvas.drawingSurfaceObject.unLock();
										if ((offBufRetained != null) && offBufRetained.isByReference()) {
											offBufRetained.geomLock.unLock();
										}
										canvas.offScreenRendering = false;

										// Issue 260 : indicate fatal error and notify error listeners
										canvas.setFatalError();
										RenderingError err = new RenderingError(RenderingError.CONTEXT_CREATION_ERROR,
												J3dI18N.getString("Renderer7"));
										err.setCanvas3D(canvas);
										err.setGraphicsDevice(canvas.graphicsConfiguration.getDevice());
										notifyErrorListeners(err);

										break doneRender;
									}
									sharedCtxTimeStamp = VirtualUniverse.mc.getContextTimeStamp();

									needToRebuildDisplayList = true;
								}

								canvas.drawingSurfaceObject.unLock();
							}
						}

						if (canvas.ctx == null) {

							// Always lock for context create
							if (!canvas.drawingSurfaceObject.renderLock()) {
								if ((offBufRetained != null) && offBufRetained.isByReference()) {
									offBufRetained.geomLock.unLock();
								}
								canvas.offScreenRendering = false;
								break doneRender;
							}

							synchronized (VirtualUniverse.mc.contextCreationLock) {
								canvas.ctx = null;
								try {
									canvas.ctx = canvas.createNewContext(sharedCtx, false);
								} catch (RuntimeException ex) {
									ex.printStackTrace();
								}

								if (canvas.ctx == null) {
									canvas.drawingSurfaceObject.unLock();
									if ((offBufRetained != null) && offBufRetained.isByReference()) {
										offBufRetained.geomLock.unLock();
									}
									canvas.offScreenRendering = false;

									// Issue 260 : indicate fatal error and notify error listeners
									canvas.setFatalError();
									RenderingError err = new RenderingError(RenderingError.CONTEXT_CREATION_ERROR,
											J3dI18N.getString("Renderer7"));
									err.setCanvas3D(canvas);
									err.setGraphicsDevice(canvas.graphicsConfiguration.getDevice());
									notifyErrorListeners(err);

									break doneRender;
								}

								if (canvas.graphics2D != null) {
									canvas.graphics2D.init();
								}

								canvas.ctxTimeStamp = VirtualUniverse.mc.getContextTimeStamp();
								listOfCtxs.add(canvas.ctx);
								listOfCanvases.add(canvas);

								for (ImageComponentRetained nc : renderBin.nodeComponentList) {
									nc.evaluateExtensions(canvas);
								}

								// enable separate specular color
								canvas.enableSeparateSpecularColor();
							}

							// create the cache texture state in canvas
							// for state download checking purpose
							if (canvas.texUnitState == null) {
								canvas.createTexUnitState();
							}

							canvas.resetImmediateRendering();
							canvas.drawingSurfaceObject.contextValidated();

							if (!canvas.useSharedCtx) {
								canvas.needToRebuildDisplayList = true;
							}
							canvas.drawingSurfaceObject.unLock();
						} else {
							if (canvas.isRunning) {
								canvas.makeCtxCurrent();
							}
						}

						if (renderBin != null) {
							if ((VirtualUniverse.mc.doDsiRenderLock) && (!canvas.drawingSurfaceObject.renderLock())) {
								if ((offBufRetained != null) && offBufRetained.isByReference()) {
									offBufRetained.geomLock.unLock();
								}
								canvas.offScreenRendering = false;
								break doneRender;
							}

							// handle free resource
							if (canvas.useSharedCtx) {
								freeResourcesInFreeList(canvas);
							} else {
								canvas.freeResourcesInFreeList(canvas.ctx);
							}

							if (VirtualUniverse.mc.doDsiRenderLock) {
								canvas.drawingSurfaceObject.unLock();
							}

							// Issue 109 : removed copyOfCvCache now that we have
							// a separate canvasViewCache for computing view frustum
							CanvasViewCache cvCache = canvas.canvasViewCache;

							// Deadlock if we include updateViewCache in
							// drawingSurfaceObject sync.
							canvas.updateViewCache(false, null, null, renderBin.geometryBackground != null);

							if ((VirtualUniverse.mc.doDsiRenderLock) && (!canvas.drawingSurfaceObject.renderLock())) {
								if ((offBufRetained != null) && offBufRetained.isByReference()) {
									offBufRetained.geomLock.unLock();
								}
								canvas.offScreenRendering = false;
								break doneRender;
							}

							int cvWidth = cvCache.getCanvasWidth();
							int cvHeight = cvCache.getCanvasHeight();
							// setup viewport
							canvas.setViewport(canvas.ctx, 0, 0, cvWidth, cvHeight);

							// rebuild the display list of all dirty renderMolecules.
							if (canvas.useSharedCtx) {
								if (needToRebuildDisplayList) {
									renderBin.updateAllRenderMolecule(this, canvas);
									needToRebuildDisplayList = false;
								}

								if (dirtyDisplayList) {
									renderBin.updateDirtyDisplayLists(canvas, dirtyRenderMoleculeList,
											dirtyDlistPerRinfoList, dirtyRenderAtomList, true);
									dirtyDisplayList = false;
								}

								// for shared context, download textures upfront
								// to minimize the context switching overhead
								int sz = textureReloadList.size();

								if (sz > 0) {
									for (j = sz - 1; j >= 0; j--) {
										textureReloadList.get(j).reloadTextureSharedContext(canvas);
									}
									textureReloadList.clear();
								}

							} else {
								// update each canvas
								if (canvas.needToRebuildDisplayList) {
									renderBin.updateAllRenderMolecule(canvas);
									canvas.needToRebuildDisplayList = false;
								}
								if (canvas.dirtyDisplayList) {
									renderBin.updateDirtyDisplayLists(canvas, canvas.dirtyRenderMoleculeList,
											canvas.dirtyDlistPerRinfoList, canvas.dirtyRenderAtomList, false);
									canvas.dirtyDisplayList = false;
								}
							}

							// lighting setup
							if (canvas.view.localEyeLightingEnable != canvas.ctxEyeLightingEnable) {
								canvas.ctxUpdateEyeLightingEnable(canvas.ctx, canvas.view.localEyeLightingEnable);
								canvas.ctxEyeLightingEnable = canvas.view.localEyeLightingEnable;
							}

							// stereo setup
							boolean useStereo = cvCache.getUseStereo();
							if (useStereo) {
								num_stereo_passes = 2;
								stereo_mode = Canvas3D.FIELD_LEFT;

								sharedStereoZBuffer = VirtualUniverse.mc.sharedStereoZBuffer;
							} else {
								num_stereo_passes = 1;
								stereo_mode = Canvas3D.FIELD_ALL;

								// just in case user set flag -
								// disable since we are not in stereo
								sharedStereoZBuffer = false;
							}

							// background geometry setup
							if (renderBin.geometryBackground != null) {
								renderBin.updateInfVworldToVpc();
							}

							// setup default render mode - render to both eyes
							canvas.setRenderMode(canvas.ctx, Canvas3D.FIELD_ALL, canvas.useDoubleBuffer);
							
							// clear background if not in stereo mode
							if (!sharedStereoZBuffer) {
								BackgroundRetained bg = renderBin.background;
								canvas.clear(bg, cvWidth, cvHeight);
							}

							// handle preRender callback
							if (VirtualUniverse.mc.doDsiRenderLock) {
								canvas.drawingSurfaceObject.unLock();
							}
							canvas.view.inCanvasCallback = true;

							try {
								canvas.preRender();
							} catch (RuntimeException e) {
								System.err.println("Exception occurred during Canvas3D callback:");
								e.printStackTrace();
							} catch (Error e) {
								// Issue 264 - catch Error so Renderer doesn't die
								System.err.println("Error occurred during Canvas3D callback:");
								e.printStackTrace();
							}
							canvas.view.inCanvasCallback = false;

							if ((VirtualUniverse.mc.doDsiRenderLock) && (!canvas.drawingSurfaceObject.renderLock())) {
								if ((offBufRetained != null) && offBufRetained.isByReference()) {
									offBufRetained.geomLock.unLock();
								}
								canvas.offScreenRendering = false;
								break doneRender;
							}
							///////////////////////////////////////////////////////////////////////////////////////////////////////
							//TODO:(and I guess a water reflection loop too?
							//Water reflection is per canvas3D and view as it requires the current camera projection

							
							// Note that lights are a renderbin issue as they are environmental (and frustum clipped for scope)
							// so light maps can be added to the light itself per render loop
							// not the scope of lights is based on visible geometry but the geometry required for renderering
							// must be the frustum plus the frustum projected at the light
							
							//TODO: check if shadowmap enabled, by having a variable based on shadow enabled lights in scene
							boolean doShadowPass = Pipeline.getPipeline() instanceof Jogl2es2Pipeline;
							if (doShadowPass) {
								
								ArrayList<LightRetained> mappedLts = new ArrayList<LightRetained>();
								// get all lights from renderbin that influence view frustum render atoms
								int sz = renderBin.renderAtoms.size();
								//TODO: is this just enormous?
								for (int n = 0; n < sz; n++) {
									RenderAtom ra = renderBin.renderAtoms.get(n);
								  
								    if (!ra.inRenderBin())
									continue;

								    LightRetained[] lights = renderBin.universe.renderingEnvironmentStructure.getInfluencingLights(ra, view);
								    for (i=0; i < lights.length; i++) {
								    	LightRetained light = lights[i];
									    if (!mappedLts.contains(light)) {									    	
									    	mappedLts.add(light);
									    	if(light.hasShadowMap) {
										    	if( light instanceof DirectionalLightRetained) {
										    		DirectionalLightRetained dlr = (DirectionalLightRetained)light;
										    	
										    		//FIXME: all calls here taht use the pipeline should ask teh canvas3d to do the actual call
										    		//TODO: check if shadow map supported on pipeline (not for ffp)
													Jogl2es2Pipeline jogl2es2Pipeline = ((Jogl2es2Pipeline)Pipeline.getPipeline());

													// TODO :give the canvas the depth buffer details so we are rendering to aFBO not the screen
													jogl2es2Pipeline.bindToShadowDepthBuffer(canvas.ctx, light);

										
													//TODO: the light should have a frustum near far and a bias figre, or the bias 
													// uniform should be handed in based on near far?, no bias is about where in the depthi is all is
													
													
													//TODO:canvas3d frustum are used by the rendermethod for culling
													//TODO: dirty hack instead of setting the canvas to a no clip plane set
													VirtualUniverse.mc.viewFrustumCulling = false;

													//tell things to use a special depth only shader
													jogl2es2Pipeline.enableOverrideShadowDepthShader(canvas.ctx, light, true);
													
													
													Transform3D lightLocation = new Transform3D();
													// flip direction to position
													Point3d eye = new Point3d(dlr.xformDirection);
													//TODO: directional lights now need a proper positions so the frustm works properly, unless they always hover over the 
													// viewport out at half the frustum size? the below puts them over 0,0 at 10 high
													eye.scale(-10f);// used to flip dir if -1
													Point3d center = new Point3d(0,0,0);
													Vector3d up = new Vector3d(0, 0, -1);													
													// to make sure the up is not collinear with z only eye
													if (eye.x == 0 && eye.y == 0) {
														up.y = 1;
													}													 
													lightLocation.lookAt( eye, center, up);
													canvas.vpcToEc = new Transform3D(lightLocation);
													canvas.vworldToEc.mul(canvas.vpcToEc, cvCache.getInfVworldToVpc());
													
													
													//this bad boy is a nice directional light orthographic projection from eye to screen 
													Transform3D lightProj = new Transform3D();
													float near_plane = 0.1f, far_plane = 64.0f;
													
													// see the JavaDoc for this method regarding opengl difference
													lightProj.ortho(-5.0f, 5.0f, -5.0f, 5.0f, near_plane, far_plane);												
													canvas.setProjectionMatrix(canvas.ctx, lightProj);													
													
													// now combine and set for the shaders to receive
													//java proj matrix have reverse z clips vs opengl!! @see  setProjectionMatrix 
													lightProj.set(new double[] {
													lightProj.mat[0],lightProj.mat[1],lightProj.mat[2],lightProj.mat[3],    
													lightProj.mat[4],lightProj.mat[5],lightProj.mat[6],lightProj.mat[7], 
													-lightProj.mat[8],-lightProj.mat[9],-lightProj.mat[10],-lightProj.mat[11], 
													lightProj.mat[12],lightProj.mat[13],lightProj.mat[14],lightProj.mat[15], }
													);		
													// we add the projection and view into one (called proj becuse we project to it)
													lightProj.mul(lightLocation);
													
													// don't point take copy, possibly not needed
													light.projMatrix = new double[] {
														lightProj.mat[0],lightProj.mat[1],lightProj.mat[2],lightProj.mat[3],    
														lightProj.mat[4],lightProj.mat[5],lightProj.mat[6],lightProj.mat[7], 
														lightProj.mat[8],lightProj.mat[9],lightProj.mat[10],lightProj.mat[11], 
														lightProj.mat[12],lightProj.mat[13],lightProj.mat[14],lightProj.mat[15], };
																							
													// render opaque geometry
													renderBin.renderOpaque(canvas);
		
													// render ordered geometry
													renderBin.renderOrdered(canvas);
		
													//TODO:  tell things to use a special depth and color type shader(for transparent textures)
		
													// render transparent geometry
													renderBin.renderTransparent(canvas);
					
													//Restore 
													VirtualUniverse.mc.viewFrustumCulling = true;
													jogl2es2Pipeline.enableOverrideShadowDepthShader(canvas.ctx, light, false);

													
												
										    	} else if( light instanceof PointLightRetained) {
										    		
										    		//TODO: and spot light? or is that just a variation on the way the cube map is shaded?
										    		
										    	}
										    	//ambient light skipped obviously
									    	}
									    }
									}
								}								
								
								// lights etc all need to be recalc as we are changing the view
								canvas.canvasDirty |= Canvas3D.VIEW_MATRIX_DIRTY;
								
								// reset the viewport
								canvas.setViewport(canvas.ctx, 0, 0, cvWidth, cvHeight);								
								
							}
///////////////////////////////////////////////////////////////////////////////////////////////							

							// render loop
							for (pass = 0; pass < num_stereo_passes; pass++) {
								canvas.setRenderMode(canvas.ctx, stereo_mode, canvas.useDoubleBuffer);

								// clear background for stereo 
								if (sharedStereoZBuffer) {
									BackgroundRetained bg = renderBin.background;
									canvas.clear(bg, cvWidth, cvHeight);
								}

								// render background geometry
								if (renderBin.geometryBackground != null) {
									// setup rendering matrices
									if (pass == 0) {
										canvas.vpcToEc = cvCache.getInfLeftVpcToEc();
										canvas.setProjectionMatrix(canvas.ctx, cvCache.getInfLeftProjection());
									} else {
										canvas.vpcToEc = cvCache.getInfRightVpcToEc();
										canvas.setProjectionMatrix(canvas.ctx, cvCache.getInfRightProjection());
									}
									canvas.vworldToEc.mul(canvas.vpcToEc, cvCache.getInfVworldToVpc());

									// render background geometry
									renderBin.renderBackground(canvas);
								}

								// setup rendering matrices
								if (pass == 0) {
									canvas.vpcToEc = cvCache.getLeftVpcToEc();
									canvas.setProjectionMatrix(canvas.ctx, cvCache.getLeftProjection());
								} else {
									canvas.vpcToEc = cvCache.getRightVpcToEc();
									canvas.setProjectionMatrix(canvas.ctx, cvCache.getRightProjection());
								}
								canvas.vworldToEc.mul(canvas.vpcToEc, cvCache.getVworldToVpc());

								synchronized (cvCache) {
									if (pass == 0) {
										canvas.setFrustumPlanes(cvCache.getLeftFrustumPlanesInVworld());
									} else {
										canvas.setFrustumPlanes(cvCache.getRightFrustumPlanesInVworld());
									}
								}

								// Force view matrix dirty for each eye.
								if (useStereo) {
									canvas.canvasDirty |= Canvas3D.VIEW_MATRIX_DIRTY;
								}

								// render opaque geometry
								renderBin.renderOpaque(canvas);

								// render ordered geometry
								renderBin.renderOrdered(canvas);

								// handle renderField callback
								if (VirtualUniverse.mc.doDsiRenderLock) {
									canvas.drawingSurfaceObject.unLock();
								}
								canvas.view.inCanvasCallback = true;
								try {
									canvas.renderField(stereo_mode);
								} catch (RuntimeException e) {
									System.err.println("Exception occurred during Canvas3D callback:");
									e.printStackTrace();
								} catch (Error e) {
									// Issue 264 - catch Error so Renderer doesn't die
									System.err.println("Error occurred during Canvas3D callback:");
									e.printStackTrace();
								}
								canvas.view.inCanvasCallback = false;
								if ((VirtualUniverse.mc.doDsiRenderLock)
									&& (!canvas.drawingSurfaceObject.renderLock())) {
									if ((offBufRetained != null) && offBufRetained.isByReference()) {
										offBufRetained.geomLock.unLock();
									}
									canvas.offScreenRendering = false;
									break doneRender;
								}

								// render transparent geometry
								renderBin.renderTransparent(canvas);

								if (useStereo) {
									stereo_mode = Canvas3D.FIELD_RIGHT;
									canvas.rightStereoPass = true;
								}
							}
							canvas.imageReady = true;
							canvas.rightStereoPass = false;

							// reset renderMode
							canvas.setRenderMode(canvas.ctx, Canvas3D.FIELD_ALL, canvas.useDoubleBuffer);

							// handle postRender callback
							if (VirtualUniverse.mc.doDsiRenderLock) {
								canvas.drawingSurfaceObject.unLock();
							}
							canvas.view.inCanvasCallback = true;

							try {
								canvas.postRender();
							} catch (RuntimeException e) {
								System.err.println("Exception occurred during Canvas3D callback:");
								e.printStackTrace();
							} catch (Error e) {
								// Issue 264 - catch Error so Renderer doesn't die
								System.err.println("Error occurred during Canvas3D callback:");
								e.printStackTrace();
							}
							canvas.view.inCanvasCallback = false;

							// end offscreen rendering
							if (canvas.offScreenRendering) {

								canvas.syncRender(canvas.ctx, true);
								canvas.endOffScreenRendering();
								canvas.offScreenRendering = false;

								// Issue 489 - don't call postSwap here for auto-offscreen,
								// since it will be called later by the SWAP operation
								if (canvas.manualRendering) {
									// do the postSwap for offscreen here
									canvas.view.inCanvasCallback = true;
									try {
										canvas.postSwap();
									} catch (RuntimeException e) {
										System.err.println("Exception occurred during Canvas 3D callback:");
										e.printStackTrace();
									} catch (Error e) {
										// Issue 264 - catch Error so Renderer doesn't die
										System.err.println("Error occurred during Canvas3D callback:");
										e.printStackTrace();
									}

									if (offBufRetained.isByReference()) {
										offBufRetained.geomLock.unLock();
									}

									canvas.view.inCanvasCallback = false;

									canvas.releaseCtx();
								}
							}

							if (MasterControl.isStatsLoggable(Level.INFO)) {
								// Instrumentation of Java 3D renderer
								long deltaTime = System.nanoTime() - startRenderTime;
								VirtualUniverse.mc.recordTime(MasterControl.TimeType.RENDER, deltaTime);
							}

						} else { // if (renderBin != null)
							if ((offBufRetained != null) && offBufRetained.isByReference()) {
								offBufRetained.geomLock.unLock();
							}
						}
					}
				}

				// clear array to prevent memory leaks
				if (opArg == RENDER) {
					m [0] = null;
				} else {
					Arrays.fill(m, 0, totalMessages, null);
				}
			}
		} catch (NullPointerException ne) {
			// Print NPE, but otherwise ignore it
			ne.printStackTrace();
			if (canvas != null) {
				// drawingSurfaceObject will safely ignore
				// this request if this is not lock before
				canvas.drawingSurfaceObject.unLock();

			}
		} catch (RuntimeException ex) {
			ex.printStackTrace();

			RenderingError err = new RenderingError(RenderingError.UNEXPECTED_RENDERING_ERROR,
					J3dI18N.getString("Renderer8"));
			err.setCanvas3D(canvas);
			if (canvas != null) {
				// drawingSurfaceObject will safely ignore
				// this request if this is not lock before
				canvas.drawingSurfaceObject.unLock();
				// Issue 260 : indicate fatal error and notify error listeners
				canvas.setFatalError();
				err.setGraphicsDevice(canvas.graphicsConfiguration.getDevice());
			}

			notifyErrorListeners(err);
		}
	}

	// resource clean up
	@Override
	void shutdown() {
		removeAllCtxs();
	}

	@Override
	void cleanup() {
		super.cleanup();
		renderMessage = new J3dMessage[1];
		rendererStructure = new RendererStructure();
		bgVworldToVpc = new Transform3D();
		sharedCtx = null;
		sharedCtxTimeStamp = 0;
		sharedCtxDrawable = null;
		dirtyRenderMoleculeList.clear();
		dirtyRenderAtomList.clear();
		dirtyDlistPerRinfoList.clear();
		textureIdResourceFreeList.clear();
		displayListResourceFreeList.clear();
		onScreen = null;
		offScreen = null;
		m = null;
		nmesg = 0;
	}

	// This is only invoked from removeCtx()/removeAllCtxs()
	// with drawingSurface already lock
	final void makeCtxCurrent(Context sharedCtx, Drawable drawable) {
		if (sharedCtx != currentCtx || drawable != currentDrawable) {
			Canvas3D.useCtx(sharedCtx, drawable);
			/*
			if(!Canvas3D.useCtx(sharedCtx, display, drawable)) {
			    Thread.dumpStack();
			    System.err.println("useCtx Fail");
			}
			*/
			currentCtx = sharedCtx;
			currentDrawable = drawable;
		}
	}

	// No need to free graphics2d and background if it is from
	// Canvas3D postRequest() offScreen rendering since the
	// user thread will not wait for it. Also we can just
	// reuse it as Canvas3D did not destroy.
	private void removeCtx(	Canvas3D cv, Drawable drawable, Context ctx, boolean resetCtx, boolean freeBackground,
							boolean destroyOffScreenBuffer) {

		synchronized (VirtualUniverse.mc.contextCreationLock) {
			if (ctx != null) {
				int idx = listOfCtxs.indexOf(ctx);
				if (idx >= 0) {
					listOfCtxs.remove(idx);
					listOfCanvases.remove(idx);
					// Issue 326 : don't check display variable here
					if ((drawable != null) && cv.added) {
						// cv.ctx may reset to -1 here so we
						// always use the ctx pass in.
						if (cv.drawingSurfaceObject.renderLock()) {
							// if it is the last one, free shared resources
							if (sharedCtx != null) {
								if (listOfCtxs.isEmpty()) {
									makeCtxCurrent(sharedCtx, sharedCtxDrawable);
									freeResourcesInFreeList(null);
									freeContextResources();
									Canvas3D.destroyContext(sharedCtxDrawable, sharedCtx);
									currentCtx = null;
									currentDrawable = null;
								} else {
									freeResourcesInFreeList(cv);
								}
								cv.makeCtxCurrent(ctx, drawable);
							} else {
								cv.makeCtxCurrent(ctx, drawable);
								cv.freeResourcesInFreeList(ctx);
							}
							cv.freeContextResources(this, freeBackground, ctx);
							Canvas3D.destroyContext(drawable, ctx);
							currentCtx = null;
							currentDrawable = null;
							cv.drawingSurfaceObject.unLock();
						}
					}
				}

				if (resetCtx) {
					cv.ctx = null;
				}

				if ((sharedCtx != null) && listOfCtxs.isEmpty()) {
					sharedCtx = null;
					sharedCtxTimeStamp = 0;
				}
				cv.ctxTimeStamp = 0;
			}

			// Fix for issue 18.
			// Since we are now the renderer thread,
			// we can safely execute destroyOffScreenBuffer.
			if (destroyOffScreenBuffer) {
				cv.destroyOffScreenBuffer(ctx, drawable);
				cv.offScreenBufferPending = false;
			}
		}
	}

	void removeAllCtxs() {
		Canvas3D cv;

		synchronized (VirtualUniverse.mc.contextCreationLock) {

			for (int i = listOfCanvases.size() - 1; i >= 0; i--) {
				cv = listOfCanvases.get(i);

				if ((cv.screen != null) && (cv.ctx != null)) {
					// Issue 326 : don't check display variable here
					if ((cv.drawable != null) && cv.added) {
						if (cv.drawingSurfaceObject.renderLock()) {
							// We need to free sharedCtx resource
							// first before last non-sharedCtx to
							// workaround Nvidia driver bug under Linux
							// that crash on freeTexture ID:4685156
							if ((i == 0) && (sharedCtx != null)) {
								makeCtxCurrent(sharedCtx, sharedCtxDrawable);
								freeResourcesInFreeList(null);
								freeContextResources();
								Canvas3D.destroyContext(sharedCtxDrawable, sharedCtx);
								currentCtx = null;
								currentDrawable = null;
							}
							cv.makeCtxCurrent();
							cv.freeResourcesInFreeList(cv.ctx);
							cv.freeContextResources(this, true, cv.ctx);
							Canvas3D.destroyContext(cv.drawable, cv.ctx);
							currentCtx = null;
							currentDrawable = null;
							cv.drawingSurfaceObject.unLock();
						}
					}
				}

				cv.ctx = null;
				cv.ctxTimeStamp = 0;
			}

			if (sharedCtx != null) {
				sharedCtx = null;
				sharedCtxTimeStamp = 0;
			}
			listOfCanvases.clear();
			listOfCtxs.clear();
		}
	}

	// handle free resource in the FreeList
	void freeResourcesInFreeList(Canvas3D cv) {
		Iterator<Integer> it;
		boolean isFreeTex = (textureIdResourceFreeList.size() > 0);
		boolean isFreeDL = (displayListResourceFreeList.size() > 0);
		int val;

		if (isFreeTex || isFreeDL) {
			if (cv != null) {
				cv.makeCtxCurrent(sharedCtx);
			}

			if (isFreeDL) {
				for (it = displayListResourceFreeList.iterator(); it.hasNext();) {
					val = it.next().intValue();
					if (val <= 0) {
						continue;
					}
					Canvas3D.freeDisplayList(sharedCtx, val);
				}
				displayListResourceFreeList.clear();
			}
			if (isFreeTex) {
				for (it = textureIdResourceFreeList.iterator(); it.hasNext();) {
					val = it.next().intValue();
					if (val <= 0) {
						continue;
					}
					if (val >= textureIDResourceTable.size()) {
						MasterControl.getCoreLogger().severe("Error in freeResourcesInFreeList : ResourceIDTableSize = "
																+ textureIDResourceTable.size() + " val = " + val);
					} else {
						TextureRetained tex = textureIDResourceTable.get(val);
						if (tex != null) {
							synchronized (tex.resourceLock) {
								tex.resourceCreationMask &= ~rendererBit;
								if (tex.resourceCreationMask == 0) {
									tex.freeTextureId(val);
								}
							}
						}

						textureIDResourceTable.set(val, null);
					}
					Canvas3D.freeTexture(sharedCtx, val);
				}
				textureIdResourceFreeList.clear();
			}
			if (cv != null) {
				cv.makeCtxCurrent(cv.ctx);
			}
		}
	}

	final void addTextureResource(int id, TextureRetained obj) {
		if (textureIDResourceTable.size() <= id) {
			for (int i = textureIDResourceTable.size(); i < id; i++) {
				textureIDResourceTable.add(null);
			}
			textureIDResourceTable.add(obj);
		} else {
			textureIDResourceTable.set(id, obj);
		}
	}

	void freeContextResources() {
		TextureRetained tex;

		for (int id = textureIDResourceTable.size() - 1; id >= 0; id--) {
			tex = textureIDResourceTable.get(id);
			if (tex == null) {
				continue;
			}
			Canvas3D.freeTexture(sharedCtx, id);
			synchronized (tex.resourceLock) {
				tex.resourceCreationMask &= ~rendererBit;
				if (tex.resourceCreationMask == 0) {
					tex.freeTextureId(id);
				}
			}
		}
		textureIDResourceTable.clear();

		// displayList is free in Canvas.freeContextResources()
	}

	/**
	 * Send a message to the notification thread, which will call the shader error listeners.
	 */
	static void notifyErrorListeners(RenderingError err) {
		J3dNotification notification = new J3dNotification();
		notification.type = J3dNotification.RENDERING_ERROR;
		notification.universe = null;//cv.view.universe;
		notification.args [0] = err;
		VirtualUniverse.mc.sendNotification(notification);
	}

	// Default rendering error listener class
	private static RenderingErrorListener defaultErrorListener = null;

	synchronized static RenderingErrorListener getDefaultErrorListener() {
		if (defaultErrorListener == null) {
			defaultErrorListener = new DefaultErrorListener();
		}

		return defaultErrorListener;
	}

	static class DefaultErrorListener implements RenderingErrorListener {
		@Override
		public void errorOccurred(RenderingError error) {
			System.err.println();
			System.err.println("DefaultRenderingErrorListener.errorOccurred:");
			error.printVerbose();
			System.exit(1);
		}
	}

}
