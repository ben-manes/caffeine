package com.github.benmanes.caffeine.cache.simulator.cache_mem_system;

import java.io.File;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigException; //.ConfigException.*;
import java.util.Scanner; 
import java.io.FileNotFoundException;

class MyConfig {
    static final String conf_file_name = "CacheTry.conf";
    static final String trace_file_name = "WikiBench.csv"; 
       
    public static Config GetConfig () {
    	System.out.println("path to the config file is " + System.getProperty("user.dir")); //$$
    	final String full_path_conf_file_name = System.getProperty("user.dir") + "\\" + conf_file_name;
        File conf_file = new File(full_path_conf_file_name);
    	if (!conf_file.isFile()) {
    		System.out.println ("Missing configuration file " + full_path_conf_file_name);
    		System.exit(1);
    	}
        return ConfigFactory.parseFile (conf_file);
    }
    
    // Returns the desired String parameter written in the configuration file
    public static String GetStringParameterFromConfFile (String str) {
    	String res = null;
    	try {
    		res = GetConfig().getString(str);
    	}
    	catch (ConfigException.Missing | ConfigException.WrongType e) { //
    		System.out.println("Missing parameter cache-size in configuration file");
    		System.exit (0);
    	}
    	return res;
    }
    
    // Returns the desired int parameter written in the configuration file
    public static double GetDoubleParameterFromConfFile (String str) {
    	double res = -1;
    	try {
    		res = GetConfig().getDouble(str);
    	}
    	catch (ConfigException.Missing | ConfigException.WrongType e) { //
    		System.out.println("Missing parameter cache-size in configuration file");
    		System.exit (0);
    	}
    	return res;
    }
    
    // Returns the desired int parameter written in the configuration file
    public static int GetIntParameterFromConfFile (String str) {
    	int res = -1;
    	try {
    		res = GetConfig().getInt(str);
    	}
    	catch (ConfigException.Missing | ConfigException.WrongType e) { //
    		System.out.println("Missing parameter cache-size in configuration file");
    		System.exit (0);
    	}
    	return res;
    }
    
    public static File GetTraceFile() {
    	String trace_file_full_path = System.getProperty("user.dir") + "\\traces\\" + trace_file_name;
    	File trace_file = new File (trace_file_full_path);   
    	if (!trace_file.isFile()) {
    		System.out.println ("Trace file " + trace_file_full_path + " does not exist");
    		System.exit(0);
    	}
    	return trace_file;
    }
    
    public static Scanner GetTraceScanner() {
    	Scanner scanner = null; 
    	try {
    		scanner = new Scanner(GetTraceFile());
    	}
    	catch (FileNotFoundException e) {
    		System.out.println("Couldn't open Scanner for reading trace file");
    		System.exit (0);   		
    	}
    	return scanner;
    }

}
