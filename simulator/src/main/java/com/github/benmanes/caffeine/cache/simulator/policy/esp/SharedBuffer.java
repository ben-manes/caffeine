package com.github.benmanes.caffeine.cache.simulator.policy.esp;


public class SharedBuffer {
  private static final SharedBuffer instance = new SharedBuffer();
  private static BaseNode buffer = null;
  //static long key1 = buffer.key;

  private int flag =0;
  // Private constructor to prevent external instantiation
  private SharedBuffer() {
    // Initialize the buffer here
    buffer = new BaseNode();
    System.out.println("Shared buffer created");
    //buffer.key = 123456;  // Setting key
    //SharedBuffer.insertData(buffer);
  }

  // Method to get the singleton instance
  public static  SharedBuffer getInstance() {
    return instance;
  }

  // Method to insert data into the shared buffer
  public static synchronized void insertData(BaseNode newData) {
    buffer= newData;


    //PRINT THE KEY
    //System.out.println("The key INSERTED  TO the buffer is: "+buffer.key);
    //System.out.println("The key INSERTED  TO the buffer is: "+buffer);

  }

  // Method to get data from the shared buffer
  public static synchronized BaseNode getData() {
    return buffer;
  }
  public static synchronized long getBufferKey() {
    return buffer.key;
  }
  public  static synchronized void setFlag(int flag){
    instance.flag = flag;
  }
  public static synchronized int getFlag(){
    return instance.flag;
  }
}
