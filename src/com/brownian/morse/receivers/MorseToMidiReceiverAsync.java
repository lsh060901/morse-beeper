package com.brownian.morse.receivers;

import com.brownian.morse.Morse;
import com.sun.istack.internal.NotNull;
import com.sun.istack.internal.Nullable;

import javax.sound.midi.*;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

/**
 * A class that receives {@link Morse.Symbol Morse symbols}, translates them to MIDI
 * instructions, and passes those to a MIDI {@link Receiver} (on Channel 0).
 *
 * This class is not thread-safe; if receiving from multiple sources, symbols should be sent to it inside synchronized blocks.
 */
public class MorseToMidiReceiverAsync implements MorseReceiverAsync {
    private final Receiver midiReceiver;
    private final BlockingQueue<SymbolTuple> symbols;

    /**
     * The MIDI identifier of the pitch to play.
     */
    private static final int NOTE = 80;

    /**
     * The identifier of a MIDI instrument that stays on when turned on, without fading.
     * @see <a href="http://soundprogramming.net/file-formats/general-midi-instrument-list/">http://soundprogramming.net/file-formats/general-midi-instrument-list/</a>
     */
    private static final int DEFAULT_INSTRUMENT = 0;

    private static final int DOT_MILLIS = 100; //how long to hold a dot
    private static final int DASH_MILLIS = 3 * DOT_MILLIS; //how long to hold a dash
    private static final int SYMBOL_BREAK_MILLIS = DOT_MILLIS; //how long to wait between symbols
    private static final int CHAR_BOUNDARY_PAUSE_MILLIS = 3 * DOT_MILLIS; //how long to wait between characters
    private static final int WORD_BOUNDARY_PAUSE_MILLIS = 5 * DOT_MILLIS; //how long to wait when a pause character is received

    /**
     * Creates a receiver that accepts {@link Morse.Symbol Morse symbols} and generates MIDI instructions for the given {@link Receiver MIDI receiver}.
     * These MIDI sounds are played as soon as Morse symbols are received.
     * @param midiReceiver the MIDI receiver for this object to control
     */
    public MorseToMidiReceiverAsync(Receiver midiReceiver){
        this.midiReceiver = midiReceiver;
        this.symbols = new LinkedBlockingQueue<>();

        /*
          Starts up a thread that plays symbols as soon as they are available, and waiting when they aren't.
         */
        new Thread(() -> { //TODO: ensure this thread is cleaned up when this object is garbage collected
            try {
                //noinspection InfiniteLoopStatement
                while(true) { //we can do an infinite loop because this thread sleeps when waiting for another item
                    SymbolTuple nextSymbolAndListener = MorseToMidiReceiverAsync.this.symbols.take(); //waits until data is ready, preventing the infinite loop problem
                    playImmediately(nextSymbolAndListener.symbol);
                    //notify the listener asynchronously, so this object isn't affected by the callback's wait or errors
                    if(nextSymbolAndListener.listener != null)
                        new Thread(nextSymbolAndListener.listener::onOperationCompleted).start();
                }
            } catch (InterruptedException e) {
                System.err.println("This should never happen:");
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * A factory method that generates a MorseToMidiReceiverAsync connected to the default MIDI {@link Receiver}.
     * @return A MorseToMidiReceiverAsync that sends MIDI commands describing received Morse code to a MIDI receiver.
     * @throws MidiUnavailableException if MIDI could not be loaded
     * @see MidiSystem#getReceiver()
     */
    @NotNull
    public static MorseToMidiReceiverAsync getReceiver() throws MidiUnavailableException{
        Receiver midiReceiver = MidiSystem.getReceiver();
        try {
            //initialize the sound. This is important, so that the instrument doesn't fade over time, but stays on until we turn it off
            midiReceiver.send(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, DEFAULT_INSTRUMENT, 0), -1);
        } catch (InvalidMidiDataException e){
            System.err.println("Could not set up instrument");
            e.printStackTrace(System.err);
        }
        return new MorseToMidiReceiverAsync(midiReceiver);
    }

    /**
     * Sends a symbol of Morse code to this receiver, which is used to generate MIDI commands.
     * @param symbol A symbol of Morse code to receive.
     */
    @Override
    public void sendSymbol(Morse.Symbol symbol) {
        addToBuffer(new SymbolTuple(symbol));
    }

    /**
     * Sends a symbol of Morse code to this receiver, which is used to generate MIDI commands.
     * When the symbol has been processed, the given listener is notified.
     * @param symbol   A symbol of Morse code to receive.
     * @param listener A listener to notify when this symbol has been processed
     */
    @Override
    public void sendSymbol(Morse.Symbol symbol, @Nullable OperationCompletedListener listener) {
        addToBuffer(new SymbolTuple(symbol, listener));
    }



    /**
     * Sends an array of {@link Morse.Symbol Morse symbols} to this receiver, which are used to generate MIDI commands.
     * @param symbols an array of Morse symbols
     */
    @Override
    public void sendSymbols(Morse.Symbol[] symbols) {
        for(Morse.Symbol c : symbols)
            addToBuffer(new SymbolTuple(c));
    }

    /**
     * Sends an array of {@link Morse.Symbol Morse symbols} to this receiver, which are used to generate MIDI commands.
     * @param symbols  an array of Morse symbols
     * @param listener a listener to notify when the last symbol has been processed
     */
    @Override
    public void sendSymbols(Morse.Symbol[] symbols, @Nullable OperationCompletedListener listener) {
        sendSymbols(Arrays.stream(symbols), listener);
    }

    /**
     * Sends a stream of {@link Morse.Symbol Morse symbols} to this receiver, which are used to generate MIDI commands.
     * @param symbolStream a stream of Morse symbols
     */
    @Override
    public void sendSymbols(Stream<Morse.Symbol> symbolStream) {
        symbolStream.map(SymbolTuple::new).forEach(this::addToBuffer);
    }

    /**
     * Sends a stream of {@link Morse.Symbol Morse symbols} to this receiver in sequence, which are used to generate MIDI commands.
     * When the last symbol has been consumed, the listener (if given) is notified.
     * @param symbolStream a stream of Morse symbols
     * @param listener     a listener to notify when the last symbol has been processed
     */
    @Override
    public void sendSymbols(Stream<Morse.Symbol> symbolStream, @Nullable OperationCompletedListener listener) {
        /* We need to attach the listener to only the last entry.
         * Therefore, add all of the ones EXCEPT the last with no listener,
         * get the last, and then add that one WITH the listener.
         */
        // Thanks to http://stackoverflow.com/questions/21426843/get-last-element-of-stream-list-in-a-one-liner
        Optional<Morse.Symbol> lastSymbolTuple = symbolStream.reduce((first, second)->{
            addToBuffer(new SymbolTuple(first));
            return second;
        });
        lastSymbolTuple.ifPresent(symbol -> addToBuffer(new SymbolTuple(symbol, listener)));
    }


    /**
     * Adds the given symbol to the translation buffer, with its listener attached.
     * @param symbolTuple the symbol (bound to a listener) to add to the buffer
     */
    private void addToBuffer(SymbolTuple symbolTuple){
        try {
            symbols.put(symbolTuple);
        } catch (InterruptedException e) {
            System.err.println("This should never happen:");
            e.printStackTrace();
        }
    }

    /**
     * Immediately plays the requested symbol, waiting synchronously until finished.
     *
     * This method requires that the MIDI Receiver in this object is not playing anything on channel 0,
     * and leaves it that way afterwards.
     *
     * If null is given, the symbol is skipped.
     *
     * @param symbol the symbol to play
     */
    private void playImmediately(@Nullable Morse.Symbol symbol) throws InterruptedException {
        if(symbol == null)
            return; //avoid a NPE from the switch statement http://stackoverflow.com/questions/23056324/why-does-java-allow-null-value-to-be-assigned-to-an-enum
        switch (symbol) {
            case DOT:
                sendMessage(true);
                Thread.sleep(DOT_MILLIS);
                sendMessage(false);
                Thread.sleep(SYMBOL_BREAK_MILLIS);
                break;
            case DASH:
                sendMessage(true);
                Thread.sleep(DASH_MILLIS);
                sendMessage(false);
                Thread.sleep(SYMBOL_BREAK_MILLIS);
                break;
            case WORD_BOUNDARY_PAUSE:
                Thread.sleep(WORD_BOUNDARY_PAUSE_MILLIS);
                break;
            case CHAR_BOUNDARY_PAUSE:
                Thread.sleep(CHAR_BOUNDARY_PAUSE_MILLIS);
                break;
        }
    }

    /**
     * Sends a message to the MIDI {@link Receiver} to immediately turn the Morse sound on or off.
     * @param isOn Whether this message will tell the Receiver to turn on the Morse sound.
     */
    private void sendMessage(boolean isOn){
        midiReceiver.send(generateMessage(isOn),-1);
    }

    /**
     * Creates a message to turn the Morse sound on or off.
     * @param isOn Whether this message will tell the Receiver to turn on the Morse sound.
     * @return a message that will tell the receiver to turn the Morse sound on or off.
     * @see #sendMessage(boolean)
     */
    private static ShortMessage generateMessage(boolean isOn){
        try {
            return new ShortMessage(ShortMessage.NOTE_ON, 0, NOTE, isOn ? 93 : 1);
        } catch (InvalidMidiDataException e){
            System.err.println("This should never happen:");
            e.printStackTrace();
            return new ShortMessage();
        }
    }

    /**
     * Binds a symbol to a listener, which will be called when the symbol is processed.
     */
    private class SymbolTuple {
        @NotNull
        final Morse.Symbol symbol;
        @Nullable
        final OperationCompletedListener listener;

        SymbolTuple(@NotNull Morse.Symbol symbol, @NotNull OperationCompletedListener listener){
            this.symbol = symbol;
            this.listener = listener;
        }

        SymbolTuple(@NotNull Morse.Symbol symbol){
            this(symbol, null);
        }
    }
}
