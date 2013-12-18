package com.claygregory.jfitbit;

/**
 * General processing exceptions during Fitbit fetches
 * @author clay
 *
 */
public class FitbitRegexException extends RuntimeException {

	private static final long serialVersionUID = 8565581489120167250L;

	public FitbitRegexException( ) {
		super( );
	}
	
	public FitbitRegexException( Throwable t ) {
		super( t );
	}
}
