package com.android.server.tv;

import android.media.AudioTrack;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import android.content.Context;
import android.os.SystemProperties;
import android.app.Service;
import android.os.SystemClock;
import android.os.Environment;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedOutputStream;

class AudioStream {
	private static final String TAG = "AudioStream_tv";

	private boolean isRecording = true;
	private boolean mIsStartup = false;
	private Thread record;
	private int mCurrOutput;
	private Context mContext;

	private static final String[] strOutDevice = {
	    "bypass",                       // bypass to SPK in codec
	    "hdmi",                         // output to hdmi
	    "speaker",                      // output to SPK
	    "usb",                          // output to usb audio
	    "bluetooth",                    // output to bluetooth
	    "hdmi,speaker,usb,bluetooth",   // output to all
	    ""                              // auto select audio device
	};

	public AudioStream(Context context) {
		mContext = context;
	}

	public void switchAudioOutput(int output) {
		Log.d(TAG, "switchAudioOutput to: "+strOutDevice[output]);

		if (output == mCurrOutput) {
			Log.d(TAG, "current output already is %d" + output);
			return;
		}

		// stop audio
		stop();

		// start audio
		start(output);
	}

	boolean start(int output) {
		Log.d(TAG, "start: "+output);

		if (mIsStartup) {
			Log.w(TAG, "already startup");
			return true;
		}
		
		mIsStartup = true;
		mCurrOutput = output;
		if (mCurrOutput == 0) {
			AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
			audioManager.setParameters("HDMIin_enable=true");
			return true;
		}

		SystemProperties.set("media.audio.device_policy", strOutDevice[mCurrOutput]);
		Log.d(TAG, "setOutput: " + strOutDevice[mCurrOutput]);

		isRecording = true;
		record = new Thread(new recordSound());
		record.start(); 

		return true;
	}

	void stop() {
		Log.d(TAG, "stop");
		if (mCurrOutput == 0) {
			AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
			audioManager.setParameters("HDMIin_enable=false");
		}

		isRecording = false;

		if (record == null)
			return;

		try {
			// wait thread finish
			record.join(300);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		mCurrOutput = 6;
		SystemProperties.set("media.audio.device_policy", strOutDevice[mCurrOutput]);
		Log.d(TAG, "setOutput: " + strOutDevice[mCurrOutput]);
		mIsStartup = false;
	}

	class recordSound implements Runnable
	{
		AudioRecord m_in_rec;
		AudioTrack m_out_trk;

		public short[] toShortArray(byte[] src) {
	
			int count = src.length >> 1;
			short[] dest = new short[count];
			for (int i = 0; i < count; i++) {
				dest[i] = (short) (src[i * 2 + 1] << 8 | src[2 * i + 0] & 0xff);
			}
			return dest;
		}

		public byte[] toByteArray(short[] src) {
	
			int count = src.length;
			byte[] dest = new byte[count << 1];
			for (int i = 0; i < count; i++) {
				dest[i * 2 + 0] = (byte) (src[i] >> 0);
				dest[i * 2 + 1] = (byte) (src[i] >> 8);
			}
	
			return dest;
		}

		public void toByteArray(byte[] dest, short[] src) {
	
			int count = src.length;
			if (dest.length/2 < count)
				count = dest.length/2;
			for (int i = 0; i < count; i++) {
				dest[i * 2 + 0] = (byte) (src[i] >> 0);
				dest[i * 2 + 1] = (byte) (src[i] >> 8);
			}
		}

		private void rampVolume(byte[] inBytes, boolean up)
		{
			short[] inShorts = toShortArray(inBytes);
			int frameCount = inShorts.length/2;
			Log.d(TAG, "ramp volume count: "+frameCount);
			float vl = up?0.0f:1.0f;
			float vlInc = (up?1.0f:-1.0f)/frameCount;
			for (int i = 0; i < frameCount; i++) {
				float a = vl*(float)inShorts[i*2];
				inShorts[i*2] = (short)a;
				inShorts[i*2+1] = (short)a;
				vl += vlInc;
			}

			toByteArray(inBytes, inShorts);
		}

		public void run()
		{
			synchronized (this) {
/*
			while (isRecording) {
				if("0".equals(SystemProperties.get("sys.hdmiin.display", "0"))) {
					SystemClock.sleep(10);
				} else {
					Log.i(TAG, "hdmiin video is coming");
					break;
				}
			}
			if (!isRecording)
				return;
*/
			int frequence = 44100;
			int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
			int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
			int m_out_buf_size = AudioTrack.getMinBufferSize(frequence,
										channelConfig,audioEncoding);
			if (m_out_buf_size<8192) {
				Log.w(TAG, "Track buffer="+m_out_buf_size+", set to 8192");
				m_out_buf_size = 8192;
			}
			m_out_trk = new AudioTrack(AudioManager.STREAM_MUSIC,frequence,
				channelConfig,
				audioEncoding, m_out_buf_size,
				AudioTrack.MODE_STREAM);

			Log.d(TAG, "set media.audio.hdmiin 1");
			SystemProperties.set("media.audio.hdmiin", "1");
			byte[] m_in_bytes;
			int m_in_buf_size = AudioRecord.getMinBufferSize(frequence, channelConfig, audioEncoding);
			Log.i(TAG, "out min: "+m_out_buf_size+", in min: "+m_in_buf_size);
			m_in_rec = new AudioRecord(MediaRecorder.AudioSource.HDMIIN, frequence, channelConfig,
				AudioFormat.ENCODING_PCM_16BIT, m_in_buf_size);
			m_in_bytes = new byte[m_in_buf_size];
			m_in_rec.startRecording();
			m_out_trk.play();
/*
			File file = null;
			DataOutputStream dos = null;
			try {
				file = new File(Environment.getExternalStorageDirectory(),
					"hdmiinauido.pcm");
				OutputStream os = new FileOutputStream(file);
				BufferedOutputStream bos = new BufferedOutputStream(os);
				dos = new DataOutputStream(bos);
			} catch (Exception e) {
				e.printStackTrace();
			}
*/
			int readBytes = 0;

			// discard 500ms audio data
			int pre_read_count = 1+(frequence*2*2)/2/m_in_buf_size;
			Log.d(TAG, "pre read count "+pre_read_count);
			while(isRecording && pre_read_count-- >= 0)
				readBytes = m_in_rec.read(m_in_bytes, 0, m_in_buf_size);
			Log.d(TAG, "pre read end");
			if (!isRecording) {
				Log.d(TAG, "exit hdmiin audio");
				m_in_rec.release();
				m_in_rec = null;
				Log.d(TAG, "set media.audio.hdmiin 0");
				SystemProperties.set("media.audio.hdmiin", "0");
				m_out_trk.release();
				m_out_trk = null;
				return;
			}

			// ramp volume for begin
			rampVolume(m_in_bytes, true);

			while (isRecording) {
				if( (readBytes > 0) && (m_out_trk != null))
					m_out_trk.write(m_in_bytes, 0, readBytes);
/*
				try {
					for (int i = 0; i < bufferReadResult; i++)
						dos.write(m_in_bytes[i]);
				} catch (Exception e) {
					e.printStackTrace();
				}
*/
				readBytes = m_in_rec.read(m_in_bytes, 0, m_in_buf_size);
			}

/*
			try {
				dos.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
*/
			}

			Log.d(TAG, "exit hdmiin audio");
			m_in_rec.release();
			m_in_rec = null;
			Log.d(TAG, "set media.audio.hdmiin 0");
			SystemProperties.set("media.audio.hdmiin", "0");

			// ramp volume for end
			Log.d(TAG, "AudioTrack setVolume 0\n");
			m_out_trk.setVolume(0.0f);
			Log.d(TAG, "AudioTrack pause\n");
			m_out_trk.pause();
			SystemClock.sleep(50);
			Log.d(TAG, "AudioTrack stop\n");
			m_out_trk.release();
			m_out_trk = null;
		}
	}
}
