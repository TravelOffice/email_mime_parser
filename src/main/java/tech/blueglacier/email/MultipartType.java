package tech.blueglacier.email;

import org.apache.james.mime4j.stream.BodyDescriptor;

public class MultipartType {
	
	private final BodyDescriptor bodyDescriptor;

	public MultipartType(BodyDescriptor bodyDescriptor){
		this.bodyDescriptor = bodyDescriptor;
	}

	public BodyDescriptor getBodyDescriptor() {
		return bodyDescriptor;
	}	
}
