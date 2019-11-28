
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Hashtable;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.TimeoutException;
import com.pi4j.io.gpio.Pin;
import com.pi4j.wiringpi.Gpio;
import com.pi4j.io.gpio.RaspiPin;

/**
 * Implements the DHT22 / AM2302 reading in Java using Pi4J.
 *
 * See sensor specification sheet for details.
 *
 * @author Doug Culnane
 */
public class DHT22 {

    /**
     * Time in nanoseconds to separate ZERO and ONE signals.
     */
    private static final int LONGEST_ZERO = 50000;

    /**
     * Minimum time in milliseconds to wait between reads of sensor.
     */
    public static final int MIN_MILLISECS_BETWEEN_READS = 2500;

    /**
     * PI4J Pin number.
     */
    private int pinNumber;

    /**
     * 40 bit Data from sensor
     */
    private byte[] data = null;

    /**
     * Value of last successful humidity reading.
     */
    private Double humidity = null;

    /**
     * Value of last successful temperature reading.
     */
    private Double temperature = null;

    /**
     * Last read attempt
     */
    private Long lastRead = null;

    /**
     * Constructor with pin used for signal.  See PI4J and WiringPI for
     * pin numbering systems.....
     *
     * @param pin
     */
    public DHT22(Pin pin) {
        pinNumber = pin.getAddress();
    }

    /**
     * Communicate with sensor to get new reading data.
     * @throws ExecutionException
     * @throws InterruptedException
     *
     * @throws Exception if failed to successfully read data.
     */
    private void getData() throws IOException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ReadSensorFuture readSensor = new ReadSensorFuture();
        Future<byte[]> future = executor.submit(readSensor);
        // Reset data
        data = new byte[5];
        try {
            data = future.get(2,TimeUnit.SECONDS);
                    readSensor.close();
        } catch (Exception e) {
            readSensor.close();
            future.cancel(true);
            executor.shutdown();
            throw new IOException(e);
        }
        readSensor.close();
        executor.shutdown();
    }

    public boolean doReadLoop() throws InterruptedException, IOException {
        Hashtable<IOException, Integer> exceptions = new Hashtable<IOException, Integer>();
        for (int i=0; i < 10; i++) {
            try {
                if (read(true)) {
                    return true;
                }
            } catch (IOException e) {
                if (Objects.isNull(exceptions.get(e))) {
                    exceptions.put(e, 1);
                } else {
                    exceptions.put(e, exceptions.get(e).intValue() + 1);
                }
            }
            Thread.sleep(DHT22.MIN_MILLISECS_BETWEEN_READS);
        }
        // return the most common exception.
        IOException returnException = null;
        int exceptionCount =  0;
        for (IOException e : exceptions.keySet()) {
            if (exceptions.get(e).intValue() > exceptionCount) {
                returnException = e;
            }
        }
        throw returnException;
    }

    /**
     * Make one new sensor reading.
     *
     * @return
     * @throws Exception
     */
    public boolean read() throws Exception {
        return read(true);
    }

    /**
     * Make a new sensor reading
     *
     * @param checkParity Should a parity check be performed?
     * @return
     * @throws ValueOutOfOperatingRangeException
     * @throws ParityCheckException
     * @throws IOException
     */
    public boolean read(boolean checkParity) throws ValueOutOfOperatingRangeException, ParityCheckException, IOException {
        checkLastReadDelay();
        lastRead = System.currentTimeMillis();
        getData();
        if (checkParity) {
            checkParity();
        }

        // Operating Ranges from specification sheet.
        // humidity 0-100
        // temperature -40~80
        double newHumidityValue = getReadingValueFromBytes(data[0], data[1]);
        if (newHumidityValue >= 0 && newHumidityValue <= 100) {
            humidity = newHumidityValue;
        } else {
            throw new ValueOutOfOperatingRangeException();
        }
        double newTemperatureValue = getReadingValueFromBytes(data[2], data[3]);
        if (newTemperatureValue >= -40 && newTemperatureValue < 85) {
            temperature = newTemperatureValue;
        } else {
            throw new ValueOutOfOperatingRangeException();
        }
        lastRead = System.currentTimeMillis();
        return true;
    }

    private void checkLastReadDelay() throws IOException {
        if (Objects.nonNull(lastRead)) {
            if (lastRead > System.currentTimeMillis() - 2000) {
                throw new IOException("Last read was under 2 seconds ago. Please wait longer between reads!");
            }
        }
    }

    private double getReadingValueFromBytes(final byte hi, final byte low) {
        ByteBuffer bb = ByteBuffer.allocate(2);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.put(hi);
        bb.put(low);
        short shortVal = bb.getShort(0);
        return new Double(shortVal) / 10;
    }

    private void checkParity() throws ParityCheckException {
        if (!(data[4] == (data[0] + data[1] + data[2] + data[3]))) {
            throw new ParityCheckException();
        }
    }

    public Double getHumidity() {
        return humidity;
    }

    public Double getTemperature() {
        return temperature;
    }


    public static void main(String[] args) throws IOException {

        String topic1 = "DHT22/temperature";
        String topic2="DHT22/humidity";
        int qos =2;
        String broker = "tcp://172.20.10.6:1883";
        MemoryPersistence persistence = new MemoryPersistence();
        String clientId = "pi";
        String userName = "pi";
        String passWord = "1234";

        try {
            MqttClient sampleClient = new MqttClient(broker, clientId, persistence);

            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            connOpts.setUserName(userName);

            connOpts.setPassword(passWord.toCharArray());
            System.out.println("Connecting to broker: "+broker);
            sampleClient.connect(connOpts);
            System.out.println("Connected");
            System.out.println("Starting DHT22");
            if (Gpio.wiringPiSetup() == -1) {
                System.out.println("GPIO wiringPiSetup Failed!");
                return;
            }

            DHT22 dht22 = new DHT22(RaspiPin.GPIO_05);
            int LOOP_SIZE = 10;
            int countSuccess = 0;
            while(countSuccess<10){
                try {
                    Thread.sleep(3000);
                    System.out.println();
                    dht22.read();
                    String content1 = String.valueOf(dht22.getHumidity());
                    String content2 = String.valueOf(dht22.getTemperature());
                    System.out.println("Humidity=" + dht22.getHumidity() +
                            "%, Temperature=" + dht22.getTemperature() + "*C");

                    MqttMessage messageh = new MqttMessage(content1.getBytes());
                    MqttMessage messaget = new MqttMessage(content2.getBytes());
                    messageh.setQos(qos);
                    messaget.setQos(qos);
                    sampleClient.publish(topic1, messaget);
                    sampleClient.publish(topic2, messageh);

                    countSuccess++;
                } catch (TimeoutException e) {
                    System.out.println("ERROR: " + e);
                } catch (Exception e) {
                    System.out.println("ERROR: " + e);
                }
            }

            sampleClient.disconnect();
            System.out.println("Disconnected");
            System.exit(0);

            //System.out.println("Read success rate: "+ countSuccess + " / " + LOOP_SIZE);
            //System.out.println("Ending DHT22");



        } catch(MqttException me) {
            System.out.println("reason "+me.getReasonCode());
            System.out.println("msg "+me.getMessage());
            System.out.println("loc "+me.getLocalizedMessage());
            System.out.println("cause "+me.getCause());
            System.out.println("excep "+me);
            me.printStackTrace();
        }




    }


    /**
     * Callable Future for reading sensor.  Allows timeout if it gets stuck.
     */
    private class ReadSensorFuture implements Callable<byte[]>, Closeable {

        private boolean keepRunning = true;

        public ReadSensorFuture() {
            Gpio.pinMode(pinNumber, Gpio.OUTPUT);
            Gpio.digitalWrite(pinNumber, Gpio.HIGH);
        }

        @Override
        public byte[] call() throws Exception {

            // do expensive (slow) stuff before we start and privoritize thread.
            byte[] data = new byte[5];
            long startTime = System.nanoTime();
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

            sendStartSignal();
            waitForResponseSignal();
            for (int i = 0; i < 40; i++) {
                while (keepRunning && Gpio.digitalRead(pinNumber) == Gpio.LOW) {
                }
                startTime = System.nanoTime();
                while (keepRunning && Gpio.digitalRead(pinNumber) == Gpio.HIGH) {
                }
                long timeHight = System.nanoTime() - startTime;
                data[i / 8] <<= 1;
                if ( timeHight > LONGEST_ZERO) {
                    data[i / 8] |= 1;
                }
            }

            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
            return data;
        }

        private void sendStartSignal() {
            // Send start signal.
            Gpio.pinMode(pinNumber, Gpio.OUTPUT);
            Gpio.digitalWrite(pinNumber, Gpio.LOW);
            Gpio.delay(10);
            Gpio.digitalWrite(pinNumber, Gpio.HIGH);
        }

        /**
         * AM2302 will pull low 80us as response signal, then
         * AM2302 pulls up 80us for preparation to send data.
         */
        private void waitForResponseSignal() {
            Gpio.pinMode(pinNumber, Gpio.INPUT);
            while (keepRunning && Gpio.digitalRead(pinNumber) == Gpio.HIGH) {
            }
            while (keepRunning && Gpio.digitalRead(pinNumber) == Gpio.LOW) {
            }
            while (keepRunning && Gpio.digitalRead(pinNumber) == Gpio.HIGH) {
            }
        }

        @Override
        public void close() throws IOException {
            keepRunning = false;

            // Set pin high for end of transmission.
            Gpio.pinMode(pinNumber, Gpio.OUTPUT);
            Gpio.digitalWrite(pinNumber, Gpio.HIGH);
        }
    }

    public class ParityCheckException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    public class ValueOutOfOperatingRangeException extends IOException {
        private static final long serialVersionUID = 1L;
    }

}



















