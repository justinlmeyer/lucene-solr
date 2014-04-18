package org.apache.lucene.store;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Random;

import org.apache.lucene.util.IOUtils;

/**
 * Simple standalone tool that forever acquires & releases a
 * lock using a specific LockFactory.  Run without any args
 * to see usage.
 *
 * @see VerifyingLockFactory
 * @see LockVerifyServer
 */ 

public class LockStressTest {

  public static void main(String[] args) throws Exception {

    if (args.length != 7) {
      System.out.println("Usage: java org.apache.lucene.store.LockStressTest myID verifierHost verifierPort lockFactoryClassName lockDirName sleepTimeMS count\n" +
                         "\n" +
                         "  myID = int from 0 .. 255 (should be unique for test process)\n" +
                         "  verifierHost = hostname that LockVerifyServer is listening on\n" +
                         "  verifierPort = port that LockVerifyServer is listening on\n" +
                         "  lockFactoryClassName = primary LockFactory class that we will use\n" +
                         "  lockDirName = path to the lock directory (only set for Simple/NativeFSLockFactory\n" +
                         "  sleepTimeMS = milliseconds to pause betweeen each lock obtain/release\n" +
                         "  count = number of locking tries\n" +
                         "\n" +
                         "You should run multiple instances of this process, each with its own\n" +
                         "unique ID, and each pointing to the same lock directory, to verify\n" +
                         "that locking is working correctly.\n" +
                         "\n" +
                         "Make sure you are first running LockVerifyServer.");
      System.exit(1);
    }

    int arg = 0;
    final int myID = Integer.parseInt(args[arg++]);

    if (myID < 0 || myID > 255) {
      System.out.println("myID must be a unique int 0..255");
      System.exit(1);
    }

    final String verifierHost = args[arg++];
    final int verifierPort = Integer.parseInt(args[arg++]);
    final String lockFactoryClassName = args[arg++];
    final String lockDirName = args[arg++];
    final int sleepTimeMS = Integer.parseInt(args[arg++]);
    final int count = Integer.parseInt(args[arg++]);

    LockFactory lockFactory;
    try {
      lockFactory = Class.forName(lockFactoryClassName).asSubclass(LockFactory.class).newInstance();          
    } catch (IllegalAccessException | InstantiationException | ClassCastException | ClassNotFoundException e) {
      throw new IOException("Cannot instantiate lock factory " + lockFactoryClassName);
    }

    File lockDir = new File(lockDirName);

    if (lockFactory instanceof FSLockFactory) {
      ((FSLockFactory) lockFactory).setLockDir(lockDir);
    }

    final InetSocketAddress addr = new InetSocketAddress(verifierHost, verifierPort);
    System.out.println("Connecting to server " + addr +
        " and registering as client " + myID + "...");
    Socket socket = new Socket();
    socket.setReuseAddress(true);
    socket.connect(addr, 500);

    OutputStream os = socket.getOutputStream();
    os.write(myID);
    os.flush();

    lockFactory.setLockPrefix("test");
    final LockFactory verifyLF = new VerifyingLockFactory(lockFactory, socket);
    final Lock l = verifyLF.makeLock("test.lock");
    final Random rnd = new Random();
    
    // wait for starting gun
    if (socket.getInputStream().read() != 43) {
      throw new IOException("Protocol violation");
    }
    
    for (int i = 0; i < count; i++) {
      boolean obtained = false;

      try {
        obtained = l.obtain(rnd.nextInt(100) + 10);
      } catch (LockObtainFailedException e) {
      }
      
      if (obtained) {
        Thread.sleep(sleepTimeMS);
        l.close();
      }
      
      if (i % 500 == 0) {
        System.out.println((i * 100. / count) + "% done.");
      }
      
      Thread.sleep(sleepTimeMS);
    }
    
    IOUtils.closeWhileHandlingException(socket);
    
    System.out.println("Finished " + count + " tries.");
  }
}
