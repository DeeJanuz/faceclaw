package com.faceclaw.sdk;

import android.os.SystemClock;

/** Content-free SDK failure information for application diagnostics. */
public final class SdkDiagnostic {
 public enum Category { LOCAL_VALIDATION, RENDERER_FAILURE, EXECUTOR_REJECTED, TRANSPORT_FAILURE, RESOURCE_FAILURE }
 public final Category category;
 public final String operation,surfaceId;
 public final long generation,timestampElapsedMs;
 public final boolean recoverable;
 public SdkDiagnostic(Category category,String operation,String surfaceId,long generation,boolean recoverable){
  if(category==null)throw new IllegalArgumentException("Missing diagnostic category");
  if(operation==null||operation.length()==0||operation.length()>64||!operation.matches("[a-z][a-z0-9_.-]*"))throw new IllegalArgumentException("Invalid diagnostic operation");
  if(surfaceId==null||surfaceId.length()>128)throw new IllegalArgumentException("Invalid diagnostic surface");
  this.category=category;this.operation=operation;this.surfaceId=surfaceId;this.generation=generation;this.recoverable=recoverable;timestampElapsedMs=SystemClock.elapsedRealtime();
 }
}
