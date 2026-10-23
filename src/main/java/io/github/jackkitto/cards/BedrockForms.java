package io.github.jackkitto.cards;

import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.cumulus.form.SimpleForm;
import java.util.*;
import java.util.function.IntConsumer;

/** Loaded only when Floodgate is enabled, so Java-only servers need none of its classes. */
final class BedrockForms {
    private BedrockForms() {}
    static boolean isBedrock(UUID id) { return FloodgateApi.getInstance().isFloodgatePlayer(id); }
    static boolean show(UUID id, String title, String content, List<String> buttons, IntConsumer response) {
        SimpleForm.Builder form = SimpleForm.builder().title(title).content(content);
        buttons.forEach(form::button);
        form.validResultHandler(result -> response.accept(result.clickedButtonId()));
        SimpleForm built = form.build();
        FloodgateApi api = FloodgateApi.getInstance();
        // Floodgate reports success for absent players, so check presence ourselves.
        return api.getPlayer(id) != null && api.sendForm(id, built);
    }
}
