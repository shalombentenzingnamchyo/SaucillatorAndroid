package com.mattfeury.saucillator.dev.android.instruments;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.json.*;

import com.mattfeury.saucillator.dev.android.SauceEngine;
import com.mattfeury.saucillator.dev.android.utilities.*;

import android.content.res.AssetManager;
import android.os.Environment;
import android.util.Log;

public class InstrumentManager {

  public static final File dataDirectory = Environment.getExternalStorageDirectory();
  public static final String assetPath = "instruments",
                             extension = ".json",
                             dataPath = dataDirectory.getAbsolutePath() + "/" + SauceEngine.DATA_FOLDER,
                             instrumentFolder = "instruments/",
                             instrumentDirPath = dataPath + instrumentFolder;
  public static String stripExtension(String file) {
    int extensionIndex = file.lastIndexOf(extension);
    return file.substring(0, extensionIndex);
  }
  
  private static final String[] preferredOrder = new String[]{"Sine", "Square", "Saw", "Noise", "Singing Saw"};

  public static ArrayList<String> getAllInstrumentNames(AssetManager man) {
    ArrayList<String> instruments = new ArrayList<String>();

    // Get built-in asset instruments
    // Sort them based on our preferredOrder defined above
    try {
      String[] assets = man.list(assetPath);
      String[] ordered = new String[assets.length];
      List<String> preferredOrdered = Arrays.asList(preferredOrder);
      for (String asset : assets) {
        String stripped = stripExtension(asset);
        int order = preferredOrdered.indexOf(stripped);
        if (order > -1 && order < assets.length)
          ordered[order] = stripped;
      }
      for (String assetName : ordered) {
        instruments.add(assetName);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    // Get user created
    File file =  new File(instrumentDirPath);
    if (file.exists() && file.isDirectory()) {
      String[] files = file.list();
      for (String fileName : files) {
        instruments.add(stripExtension(fileName));
      }
    }
    
    return instruments;
  }

  public static String getAssetPath(String filename) {
    return assetPath + "/" + filename + extension;
  }
  public static boolean isInternal(AssetManager man, String name) {
    boolean isInternal = true;

    try {
      man.open(getAssetPath(name));
    } catch(Exception e) {
      isInternal = false;
    }

    return isInternal;
  }

  public static ComplexOsc getInstrument(AssetManager man, String name) {
    boolean isInternal = isInternal(man ,name);
    return getInstrument(man, name, isInternal);
  }

  public static ComplexOsc getInstrument(AssetManager man, String name, boolean internal) {
    try {
      JSONObject json;
      if (internal) {
        json = getJsonForInternalInstrument(man, name);
      } else {
        json = getJsonForCustomInstrument(name);
      }
      return decomposeJsonInstrument(man, json);
    } catch (Exception e) {
      e.printStackTrace();
      Log.e("INSTR SAUCE", "bad instrument " + e.toString());
    }
    
    return null;
  }

  private static JSONObject getJsonForCustomInstrument(String name) throws Exception {
    FileInputStream stream = new FileInputStream(new File(instrumentDirPath + name + extension));

    String jsonString = "";
    try {
      FileChannel fc = stream.getChannel();
      MappedByteBuffer bb = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
      /* Instead of using default, pass in a decoder. */
      jsonString = Charset.defaultCharset().decode(bb).toString();
    }
    finally {
      stream.close();
    }

    JSONObject json = new JSONObject(jsonString);
    json.put("isInternal", false);
    return json;
  }

  private static JSONObject getJsonForInternalInstrument(AssetManager man, String name) throws Exception {
    InputStream is = man.open(getAssetPath(name));
    Writer writer = new StringWriter();
    char[] buffer = new char[1024];
    try {
      Reader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
      int n;
      while ((n = reader.read(buffer)) != -1) {
        writer.write(buffer, 0, n);
      }
    } catch(Exception e) {
    } finally {
      is.close();
    }

    String jsonString = writer.toString();
    JSONObject json = new JSONObject(jsonString);
    json.put("isInternal", true);
    return json;
  }

  private static ComplexOsc decomposeJsonInstrument(AssetManager man, JSONObject json) throws Exception {
    // Lookup and create timbre
    ComplexOsc instrument = new ComplexOsc();

    String name = json.optString("name", "Unknown");
    instrument.setName(name);
    
    boolean isInternal = json.optBoolean("isInternal", false);
    instrument.setInternal(isInternal);

    JSONArray timbres = json.getJSONArray("timbre");
    int numHarmonics = timbres.length(),
        validHarmonics = numHarmonics;

    float totalAmp = 0;
    for (int i = 0; i < numHarmonics; i++) {
      JSONObject timbre = timbres.getJSONObject(i);
      String timbreId = timbre.optString("id", "sine");

      int harmonic = timbre.optInt("harmonic", 1);
      int phase = timbre.optInt("phase", 0);
      float amplitude = (float)timbre.optDouble("amplitude", 1.0);

      totalAmp += amplitude;

      // It may have been deleted.
      try {
        Oscillator osc = getOscillatorForTimbre(man, timbreId);
        osc.setPhase(phase);
        osc.setHarmonic(harmonic);
        osc.setAmplitude(amplitude);

        instrument.fill(osc);
      } catch (Exception e) {
        validHarmonics--;
      }
    }

    // TODO show warning message that not all timbres were deserializable
    //if (validHarmonics != numHarmonics)

    // scale amplitude values so that they sum to MAX_AMPLITUDE.
    float factor = ComplexOsc.MAX_AMPLITUDE / totalAmp;
    for (int i = 0; i < validHarmonics; i++) {
      Oscillator osc = instrument.getComponent(i);
      osc.factorAmplitude(factor);
    }

    // Lookup and modify FX
    try {
      JSONObject fx = json.getJSONObject("fx");

      // Lag
      try {      
        final float lag = (float)fx.optDouble("lag", 0);
        instrument.setLag(lag);
      } catch (Exception e) {}

      // LFO
      try {
        JSONObject lfo = fx.getJSONObject("lfo");
        final int rate = lfo.optInt("rate", 0);
        final int depth = lfo.optInt("depth", 0);

        instrument.setModRate(rate);
        instrument.setModDepth(depth);
      } catch (Exception e) {}

      // Delay
      try {
        JSONObject delay = fx.getJSONObject("delay");
        final int time = delay.optInt("time", 0);
        final float decay = (float) delay.optDouble("decay", 0);

        instrument.setDelayRate(time);
        instrument.setDelayDecay(decay);
      } catch (Exception e) {}

      // Envelope
      try {
        JSONObject envelope = fx.getJSONObject("envelope");
        final float  attack = (float)envelope.optDouble("attack", 0.5),
                     release = (float)envelope.optDouble("release", 0.5);

        instrument.setAttack(attack);
        instrument.setRelease(release);
      } catch (Exception e) {}

    } catch (Exception e) {}

    return instrument;
  }

  public static Oscillator getOscillatorForTimbre(AssetManager man, String id) {
    if ("Sine".equals(id))
      return new Sine();
    else if ("Saw".equals(id))
      return new Saw();
    else if ("Square".equals(id))
      return new Square();
    else if ("Noise".equals(id))
      return new Noise();
    else {
      ComplexOsc osc = getInstrument(man, id);
      return (osc != null) ? osc.resetEffects() : null;
    }
  }

  public static Box<Boolean> isValidInstrumentName(AssetManager man, String name) {
    if (isInternal(man, name))
      return new Failure<Boolean>("The name is already in use internally.");
    else if (name.indexOf("*") != -1)
      return new Failure<Boolean>("Invalid character: *");
    else
      return new Full<Boolean>(true);
  }

  public static Box<ComplexOsc> saveInstrument(AssetManager man, ComplexOsc osc) {
    boolean success = true;
    String name = osc.getName();

    Box<Boolean> validName = isValidInstrumentName(man, name);
    if (validName.isFailure())
      return new Failure<ComplexOsc>("Invalid name. " + validName.getFailure());

    try {
      File file = new File(instrumentDirPath + name + extension);
      FileWriter writer = new FileWriter(file, false);
      JSONObject json = decomposeInstrumentToJson(osc);
      
      writer.write(json.toString());
      writer.flush();
      writer.close();
    } catch(Exception e) {
      success = false;
      e.printStackTrace();
    }
    if (success)
      return new Full<ComplexOsc>(osc);
    else
      return new Empty<ComplexOsc>();
  }
  public static Box<Boolean> deleteInstrument(AssetManager man, String name) {
    boolean success = true;
    
    if (isInternal(man, name))
      return new Failure<Boolean>("Invalid name. You cannot delete a built-in instrument.");

    try {
      File file = new File(instrumentDirPath + name + extension);
      success = file.delete();
    } catch(Exception e) {
      success = false;
      e.printStackTrace();
    }
    if (success)
      return new Full<Boolean>(true);
    else
      return new Empty<Boolean>();
  }

  public static JSONObject decomposeInstrumentToJson(ComplexOsc osc) throws JSONException {
    JSONObject json = new JSONObject();

    String name = osc.getName();
    json.put("name", name);
    
    // Timbre
    JSONArray timbres = new JSONArray();
    for (Oscillator timbre : osc.getComponents()) {
      String timbreName = timbre.getName();
      if (timbreName != name) {
        JSONObject timbreJson = new JSONObject();
        timbreJson.put("id", timbreName);
        
        final int harmonic = timbre.getHarmonic(),
                  phase = timbre.getPhase();
        final float amplitude = timbre.getAmplitude();
        timbreJson.put("harmonic", harmonic);
        timbreJson.put("phase", phase);
        timbreJson.put("amplitude", amplitude);
        timbres.put(timbreJson);
      }
    }
    
    json.put("timbre", timbres);
      
    // FX
    JSONObject fx = new JSONObject();

    // Lag
    double lag = osc.getLag(); 
    fx.put("lag", lag);

    // LFO
    JSONObject lfo = new JSONObject();
    final int rate = osc.getModRate(),
              depth = osc.getModDepth();
    
    lfo.put("rate", rate);
    lfo.put("depth", depth);
    fx.put("lfo", lfo);

    // Delay
    JSONObject delay = new JSONObject();
    final int time = osc.getDelayRate();
    final double decay = osc.getDelayDecay();
    
    delay.put("time", time);
    delay.put("decay", decay);
    fx.put("delay", delay);
    
    // Envelope
    JSONObject envelope = new JSONObject();
    final double attack = osc.getAttack(),
                 release = osc.getRelease();
    
    envelope.put("attack", attack);
    envelope.put("release", release);
    fx.put("envelope", envelope);

    json.put("fx", fx);

    return json;
  }

  public static ComplexOsc copyInstrument(AssetManager man, ComplexOsc osc) {
    try {
      JSONObject json = decomposeInstrumentToJson(osc);
      ComplexOsc copy = decomposeJsonInstrument(man, json);
      return copy;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }
  public static Oscillator copyInstrumentForTimbre(Oscillator osc) {
    // TODO have this do json like the above. We can't use decomposeInstrument though
    // because this isn't guaranteed to have any FX
    return (Oscillator) Utilities.deepCopy(osc);
  }

}

