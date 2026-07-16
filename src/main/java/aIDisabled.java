/**
 * Null-object AI backend used when no local model is configured.
 * All calls are no-ops that report "not enabled".
 */
public class aIDisabled implements aiInterface {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String getSingle_AI_Response(String systemPrompt, String userPrompt, boolean fakeResponse) {
        return "";
    }

    @Override
    public String addUserQueryToConversation(String systemPrompt, String userPrompt) {
        return "";
    }

    @Override
    public void resetConversationContext() {}
}
