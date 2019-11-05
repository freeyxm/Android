package com.github.freeyxm.audio;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.media.*;
import android.os.Build;
import android.util.SparseArray;
import android.util.SparseIntArray;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class CSoundPool
{
	private final int m_sampleRate = 44100;
	private final int m_numChannels = 2;
	private final int m_maxFrame = m_sampleRate * 3; // 3s
	private float[] m_audioBuffer = new float[m_maxFrame * m_numChannels];

	private class AudioData
	{
		public float[] sampleBuffer = null;
		public int numFrames = 0;
		public int numChannels = 0;
	}

	private class AudioTrackInfo
	{
		public AudioTrack audioTrack = null;

		public AudioTrackInfo(AudioTrack track)
		{
			this.audioTrack = track;
		}

		public void play()
		{
			if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED)
			{
				audioTrack.play();
			}
		}

		public void stop()
		{
			if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED)
			{
				audioTrack.pause();
				audioTrack.flush();
				audioTrack.stop();
			}
		}
	}

	private class TrackIndex
	{
		public int track_index = 0;
		public int priority = 0;

		public TrackIndex(int track_index, int priority)
		{
			this.track_index = track_index;
			this.priority = priority;
		}
	}

	private SparseArray<AudioData> m_audioDataMap = new SparseArray<AudioData>(); // sound_id=>AudioData
	private SparseIntArray m_playSoundMap = new SparseIntArray(); // stream_id=>track_id
	private List<AudioTrackInfo> m_trackList = null;
	private Stack<Integer> m_freeTrack = new Stack<Integer>(); // track_index
	private LinkedList<TrackIndex> m_playTrack = new LinkedList<TrackIndex>();
	private int m_sound_id = 0;
	private int m_stream_id = 0;
	private Activity m_activity = null;

	public CSoundPool(Activity activity, int maxStreams)
	{
		m_activity = activity;
		initSoundTracks(maxStreams);
	}

	/**
	 * Load Sound. Only support pcm wav file.
	 * 
	 * @param path
	 * @return error code (<0) on error, sound id on success.
	 */
	public int loadSound(String path, boolean isAsset)
	{
		WavFile wavFile = null;
		try
		{
			if (isAsset)
			{
				wavFile = WavFile.loadWavFile(m_activity.getAssets().open(path));
			}
			else
			{
				wavFile = WavFile.openWavFile(new File(path));
			}

			if (wavFile.getSampleRate() != m_sampleRate)
			{
				return -1002;
			}
			int numChannels = wavFile.getNumChannels();
			if (numChannels != m_numChannels)
			{
				return -1003;
			}
			int validBits = wavFile.getValidBits();
			if (validBits != 16)
			{
				return -1004;
			}
			int numFrames = (int) wavFile.getNumFrames();
			if (numFrames > m_maxFrame)
			{
				numFrames = m_maxFrame;
			}
			if (numFrames <= 0)
			{
				return -1005;
			}

			AudioData audioData = new AudioData();
			audioData.numFrames = numFrames;
			audioData.numChannels = numChannels;
			audioData.sampleBuffer = new float[audioData.numFrames * audioData.numChannels];

			int numFramesToRead = wavFile.readFrames(audioData.sampleBuffer, numFrames);
			if (numFramesToRead < numFrames)
			{
				audioData.numFrames = numFramesToRead;
			}

			int sound_id = ++m_sound_id;
			m_audioDataMap.put(sound_id, audioData);
			return sound_id;
		}
		catch (Exception e)
		{
			return -1001;
		}
		finally
		{
			if (wavFile != null)
			{
				try
				{
					wavFile.close();
				}
				catch (IOException e)
				{
				}
			}
		}
	}

	/**
	 * Unload Sound.
	 * 
	 * @param sound_id
	 *            return by {@link #loadSound(...)}.
	 */
	public void unloadSound(int sound_id)
	{
		m_audioDataMap.remove(sound_id);
	}

	/**
	 * Play Sound.
	 * 
	 * @param sound_id
	 *            return by {@link #loadSound(...)}.
	 * @param priority
	 * @param loop
	 * @return error code (<0) on error, stream id on success.
	 */
	public int playSound(int sound_id, float volume, int priority, int loop)
	{
		AudioData audioData = m_audioDataMap.get(sound_id);
		if (audioData == null)
		{
			return -1001;
		}

		int trackIndex = -1;
		if (!m_freeTrack.isEmpty())
		{
			trackIndex = m_freeTrack.pop();
		}
		else
		{
			trackIndex = popPlayTrack().track_index;
		}

		AudioTrackInfo track = getAudioTrack(trackIndex);
		track.stop();

		setAudioData(audioData);
		int ret = track.audioTrack.write(m_audioBuffer, 0, m_audioBuffer.length, AudioTrack.WRITE_BLOCKING);
		if (ret < 0)
		{
			return ret;
		}
		ret = track.audioTrack.setLoopPoints(0, audioData.numFrames - 1, loop);
		if (ret != AudioTrack.SUCCESS)
		{
			return ret;
		}
		// PlaybackParams params = new PlaybackParams();
		// params.setSpeed(1.0f);
		// track.audioTrack.setPlaybackParams(params);
		// track.audioTrack.setPlaybackRate(m_sampleRate);
		track.audioTrack.setVolume(volume);
		track.play();

		int stream_id = ++m_stream_id;
		pushPlayTrack(new TrackIndex(trackIndex, priority));
		m_playSoundMap.put(stream_id, trackIndex);
		return stream_id;
	}

	/**
	 * Stop Sound.
	 * 
	 * @param stream_id
	 *            retun by {@link #playSound(...)}.
	 */
	public void stopSound(int stream_id)
	{
		int index = m_playSoundMap.indexOfKey(stream_id);
		if (index < 0)
		{
			return;
		}

		int trackIndex = m_playSoundMap.valueAt(index);
		removePlayTrack(trackIndex);
		m_playSoundMap.removeAt(index);
		m_freeTrack.push(trackIndex);

		AudioTrackInfo track = getAudioTrack(trackIndex);
		if (track != null)
		{
			track.stop();
		}
	}

	public void stopAllSound()
	{
		for (AudioTrackInfo track : m_trackList)
		{
			track.stop();
		}
		for (TrackIndex info : m_playTrack)
		{
			m_freeTrack.push(info.track_index);
		}
		m_playTrack.clear();
		m_playSoundMap.clear();
	}

	public void release()
	{
		stopAllSound();
		for (AudioTrackInfo track : m_trackList)
		{
			track.audioTrack.release();
		}
		m_audioDataMap.clear();
	}

	private TrackIndex popPlayTrack()
	{
		return m_playTrack.removeFirst();
	}

	private void pushPlayTrack(TrackIndex track)
	{
		int index = 0;
		Iterator<TrackIndex> it = m_playTrack.iterator();
		while (it.hasNext())
		{
			TrackIndex info = it.next();
			if (info.priority > track.priority)
			{
				m_playTrack.add(index, track);
				return;
			}
			++index;
		}
		m_playTrack.addLast(track);
	}

	private void removePlayTrack(int track_index)
	{
		Iterator<TrackIndex> it = m_playTrack.iterator();
		while (it.hasNext())
		{
			TrackIndex info = it.next();
			if (info.track_index == track_index)
			{
				it.remove();
				break;
			}
		}
	}

	private void setAudioData(AudioData audioData)
	{
		int length = audioData.numFrames * audioData.numChannels;
		if (length > m_audioBuffer.length)
		{
			length = m_audioBuffer.length;
		}

		System.arraycopy(audioData.sampleBuffer, 0, m_audioBuffer, 0, length);

		if (length < m_audioBuffer.length)
		{
			Arrays.fill(m_audioBuffer, length, m_audioBuffer.length, 0);
		}
	}

	private AudioTrackInfo getAudioTrack(int index)
	{
		return m_trackList.get(index);
	}

	private void initSoundTracks(int maxStreams)
	{
		m_trackList = new ArrayList<AudioTrackInfo>(maxStreams);
		for (int i = 0; i < maxStreams; ++i)
		{
			AudioTrack audioTrack = buildAudioTrack();
			m_trackList.add(new AudioTrackInfo(audioTrack));
			m_freeTrack.push(i);
		}
	}

	@SuppressLint("NewApi")
	private AudioTrack buildAudioTrack()
	{
		AudioAttributes.Builder attrBuilder = new AudioAttributes.Builder();
		attrBuilder.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
		attrBuilder.setUsage(AudioAttributes.USAGE_GAME);
		attrBuilder.setFlags(0);
		AudioAttributes attributes = attrBuilder.build();

		AudioFormat.Builder formatBuilder = new AudioFormat.Builder();
		formatBuilder.setSampleRate(m_sampleRate);
		formatBuilder.setEncoding(AudioFormat.ENCODING_PCM_FLOAT);
		formatBuilder.setChannelMask(AudioFormat.CHANNEL_OUT_MONO);
		// formatBuilder.setChannelIndexMask(0x3); // 2 channels.
		AudioFormat format = formatBuilder.build();

		int bufferSizeInBytes = m_maxFrame * m_numChannels * Float.BYTES;

		AudioTrack track;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
		{
			track = new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
					.setBufferSizeInBytes(bufferSizeInBytes).setTransferMode(AudioTrack.MODE_STATIC)
					.setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE).build();
		}
		else
		{
			track = new AudioTrack(attributes, format, bufferSizeInBytes, AudioTrack.MODE_STATIC,
					AudioManager.AUDIO_SESSION_ID_GENERATE);
		}
		return track;
	}
}
