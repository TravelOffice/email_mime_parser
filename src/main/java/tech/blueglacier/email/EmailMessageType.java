package tech.blueglacier.email;

public class EmailMessageType {

	private final EmailMessageTypeHierarchy hierarchy;

	public EmailMessageTypeHierarchy getEmailMessageTypeHierarchy() {
		return hierarchy;
	}

	public EmailMessageType(EmailMessageTypeHierarchy hierarchy) {
		this.hierarchy = hierarchy;
	}
	
	public enum EmailMessageTypeHierarchy {
		parent, child
	}
}


