package milkman.plugin.test.editor;

import com.jfoenix.controls.JFXCheckBox;
import javafx.scene.control.Tab;
import lombok.extern.slf4j.Slf4j;
import milkman.domain.RequestContainer;
import milkman.plugin.test.domain.TestAspect;
import milkman.ui.plugin.RequestAspectEditor;
import milkman.utils.fxml.GenericBinding;

import static milkman.utils.fxml.FxmlBuilder.VboxExt;

@Slf4j
public class TestAspectScenarioEditor implements RequestAspectEditor {

	private JFXCheckBox cbFailOnFirst;
	private JFXCheckBox cbPropagateEnvironment;

	private final GenericBinding<TestAspect, Boolean> stopOnFirstFailureBinding = GenericBinding.of(TestAspect::isStopOnFirstFailure, TestAspect::setStopOnFirstFailure);
	private final GenericBinding<TestAspect, Boolean> propagateEnvBinding = GenericBinding.of(TestAspect::isPropagateResultEnvironment, TestAspect::setPropagateResultEnvironment);


	@Override
	public Tab getRoot(RequestContainer request) {
		TestAspect testAspect = request.getAspect(TestAspect.class)
				.orElseThrow(() -> new IllegalArgumentException("missing test aspect"));

		var content = new TestAspectScenarioEditorFxml(this);

		stopOnFirstFailureBinding.bindTo(cbFailOnFirst.selectedProperty(), testAspect);
		stopOnFirstFailureBinding.addListener(c -> request.setDirty(true));

		propagateEnvBinding.bindTo(cbPropagateEnvironment.selectedProperty(), testAspect);
		propagateEnvBinding.addListener(c -> request.setDirty(true));

		return new Tab("Scenario", content);
	}


	@Override
	public boolean canHandleAspect(RequestContainer request) {
		return request.getAspect(TestAspect.class).isPresent();
	}




	public static class TestAspectScenarioEditorFxml extends VboxExt {
		private final TestAspectScenarioEditor controller;

		public TestAspectScenarioEditorFxml(TestAspectScenarioEditor controller) {
			this.controller = controller;
			controller.cbFailOnFirst = add(new JFXCheckBox("Stop on first failure"));
			controller.cbPropagateEnvironment = add(new JFXCheckBox("Propagate environment changes"));
			getStyleClass().add("generic-content-pane");

		}
	}



}
