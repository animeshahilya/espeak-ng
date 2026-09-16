/*
TGSpeechBox — Speech wave generator interface.
Copyright 2014 NV Access Limited.
Copyright 2025-2026 Tamas Geczy.
Licensed under the MIT License. See LICENSE for details.
*/

#ifndef TGSPEECHBOX_SPEECHWAVEGENERATOR_H
#define TGSPEECHBOX_SPEECHWAVEGENERATOR_H

#include "frame.h"
#include "waveGenerator.h"
#include "voicingTone.h"

class SpeechWaveGenerator: public WaveGenerator {
	public:
	static SpeechWaveGenerator* create(int sampleRate); 
	virtual void setFrameManager(FrameManager* frameManager)=0;
	
	/**
	 * Set voicing tone parameters for DSP-level voice quality adjustments.
	 * This is an optional API extension - if never called, defaults are used.
	 * 
	 * @param tone  Pointer to voicing tone parameters. If NULL, resets to defaults.
	 */
	virtual void setVoicingTone(const speechPlayer_voicingTone_t* tone)=0;
	
	/**
	 * Get current voicing tone parameters.
	 * 
	 * @param tone  Output pointer to receive current parameters.
	 */
	virtual void getVoicingTone(speechPlayer_voicingTone_t* tone)=0;

	/**
	 * Set output gain applied before the limiter.
	 *
	 * Each platform's audio output chain has different amplification.
	 * By applying gain inside the DSP (before the limiter), all platforms
	 * get identical clipping and limiting behavior for the same phoneme
	 * data.  Default is 1.0 (no gain).
	 */
	virtual void setOutputGain(double gain)=0;

	/**
	 * Set time-stretch factor for DSP-level rate boost.
	 * 1.0 = normal (no stretching). 2.0 = skip every other glottal cycle
	 * for 2x speedup without formant compression. Uses pitch-synchronous
	 * cycle skipping with linear crossfade at boundaries.
	 */
	virtual void setTimeStretch(double factor)=0;
};

#endif
