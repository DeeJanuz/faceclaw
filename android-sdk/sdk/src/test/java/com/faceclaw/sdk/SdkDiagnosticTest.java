package com.faceclaw.sdk;

import org.junit.Test;
import static org.junit.Assert.*;

public class SdkDiagnosticTest {
 @Test public void diagnosticIsBoundedAndContentFree(){SdkDiagnostic diagnostic=new SdkDiagnostic(SdkDiagnostic.Category.RENDERER_FAILURE,"render","window",4,true);assertEquals(SdkDiagnostic.Category.RENDERER_FAILURE,diagnostic.category);assertEquals("render",diagnostic.operation);assertEquals("window",diagnostic.surfaceId);assertEquals(4,diagnostic.generation);assertTrue(diagnostic.recoverable);assertTrue(diagnostic.timestampElapsedMs>=0);}
 @Test public void invalidDiagnosticTokensAreRejected(){try{new SdkDiagnostic(SdkDiagnostic.Category.LOCAL_VALIDATION,"Render","",0,false);fail("uppercase operation must fail");}catch(IllegalArgumentException expected){}try{new SdkDiagnostic(SdkDiagnostic.Category.LOCAL_VALIDATION,"", "",0,false);fail("empty operation must fail");}catch(IllegalArgumentException expected){}}
}
