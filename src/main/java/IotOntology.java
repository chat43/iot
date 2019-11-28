import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import com.google.common.base.Optional;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.util.SimpleIRIMapper;
import org.semanticweb.owlapi.util.AutoIRIMapper;
import org.semanticweb.owlapi.util.DefaultPrefixManager;
import org.swrlapi.core.SWRLAPIRule;
import org.swrlapi.core.SWRLRuleEngine;
import org.semanticweb.owlapi.model.PrefixManager;
import com.clarkparsia.pellet.owlapiv3.PelletReasoner;
import com.clarkparsia.pellet.owlapiv3.PelletReasonerFactory;


public class IotOntology {
    public OWLOntologyManager ontologyManager;
    public OWLOntology ontology;
    public OWLDataFactory factory;
    public  PelletReasoner reasoner;
    public PrefixManager pm = new DefaultPrefixManager("http://www.seido.fr/seito/siot#");
    public PrefixManager pm1 = new DefaultPrefixManager("https://w3id.org/saref#");
    public PrefixManager pm2 = new DefaultPrefixManager("http://www.seido.fr/seito/kernel#");

//Construction method, load ontology file
    public IotOntology(File file){
        readOntology(file);
    }

// Step1： load ontology
    public void readOntology(File file) {
        ontologyManager = OWLManager.createOWLOntologyManager();
        //Add imported ontology
        ontologyManager.getIRIMappers().add(new SimpleIRIMapper(IRI.create("http://www.seido.fr/seito/smarthome"),
                IRI.create("file:./SEITO_SHO.owl")));
        try {
            ontology = ontologyManager.loadOntologyFromOntologyDocument(file);
            factory = ontologyManager.getOWLDataFactory();
        } catch (OWLOntologyCreationException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }






//Step2: Value of sensors changed in reality. Then values of sensors should be changed in ontology.

    public void changeValueOfSensorMeasurement(String value, String sensorMeasurement){

        OWLIndividual sensorMeasurementInstance = factory.getOWLNamedIndividual(":"+sensorMeasurement, pm);
        // get dataproperty hasvalue.
        OWLDataProperty hasValueProperty = factory.getOWLDataProperty(":" + "hasValue", pm1);
        Collection<OWLLiteral> valueList = EntitySearcher.getDataPropertyValues(sensorMeasurementInstance,hasValueProperty,ontology);
        if(valueList != null){
            for (OWLLiteral vl : valueList) {
                System.out.println(vl);
                OWLAxiom dataAssertion = factory.getOWLDataPropertyAssertionAxiom(hasValueProperty, sensorMeasurementInstance, vl);
                ontologyManager.removeAxiom(ontology, dataAssertion);
            }
        }
            OWLAxiom  dataAssertion1 = factory.getOWLDataPropertyAssertionAxiom(hasValueProperty, sensorMeasurementInstance, value);
            ontologyManager.addAxiom(ontology, dataAssertion1);
        try {
            ontologyManager.saveOntology(ontology);
        } catch (OWLOntologyStorageException e1) {
            e1.printStackTrace();
        }
    }






//Step3: start reasoner or synchronize reasoner.
    public void startReasoner() {
        reasoner = PelletReasonerFactory.getInstance().createReasoner(ontology);

    }




// Step4: Update actuator state after reasoner in the ontology. Return the new actuator state. If there is no change return null.
    public String updateActuatorState(String room, String actuator) {
        OWLNamedIndividual actuatorIndividual = factory.getOWLNamedIndividual(":" + actuator, pm);
        OWLNamedIndividual roomIndividual = factory.getOWLNamedIndividual(":" + room, pm);
        OWLObjectProperty hasState = factory.getOWLObjectProperty(":" + "hasState", pm1);
        OWLObjectProperty hasTargetState = factory.getOWLObjectProperty(":" + "hasTargetState", pm2);
        OWLObjectProperty hasFinalState = factory.getOWLObjectProperty(":" + "hasFinalState", pm2);
        OWLObjectProperty hasOrchestrator = factory.getOWLObjectProperty(":" + "hasOrchestrator", pm2);

        Set<OWLNamedIndividual> orchestrator = reasoner.getObjectPropertyValues(roomIndividual, hasOrchestrator).getFlattened();
        Set<OWLNamedIndividual> objectAssertionT = reasoner.getObjectPropertyValues(actuatorIndividual, hasTargetState).getFlattened();
        Set<OWLNamedIndividual> objectAssertionF = reasoner.getObjectPropertyValues(actuatorIndividual, hasFinalState).getFlattened();

        OWLNamedIndividual newState = decideState(objectAssertionF, objectAssertionT, orchestrator);

        if(newState!=null){
            Set<OWLNamedIndividual> oldStates = reasoner.getObjectPropertyValues(actuatorIndividual, hasState).getFlattened();
            if(!oldStates.isEmpty()){
                OWLNamedIndividual oldState = oldStates.iterator().next();
                if( oldState !=newState){
                    OWLAxiom oldStateAssertion = factory.getOWLObjectPropertyAssertionAxiom(hasState, actuatorIndividual, oldState);
                    ontologyManager.removeAxiom(ontology, oldStateAssertion);
                    OWLAxiom newStateAssertion = factory.getOWLObjectPropertyAssertionAxiom(hasState, actuatorIndividual, newState);
                    ontologyManager.addAxiom(ontology, newStateAssertion);
                }
            }
            try {
                ontologyManager.saveOntology(ontology);
            } catch (OWLOntologyStorageException e1) {
                e1.printStackTrace();
            }
            //return the state in string.
            String sState = newState.getIRI().toString();
            //System.out.println(sState.substring(sState.indexOf("#")+1)); retrun offState/onState
            return sState.substring(sState.indexOf("#")+1);
        }else{
             return null;
        }

    }




//Decide new states of actuators. TargetState->new state or FinalState -> new state?
    public OWLNamedIndividual decideState(Set<OWLNamedIndividual> objectAssertionF1, Set<OWLNamedIndividual> objectAssertionT1,Set<OWLNamedIndividual> orchestrator1){
        if(!objectAssertionF1.isEmpty()){
            return objectAssertionF1.iterator().next();
        }else if((!objectAssertionT1.isEmpty()) && orchestrator1.isEmpty()){
            return objectAssertionT1.iterator().next();
        }else{
            return null;
        }
    }



    public static void main(String[] args) {

        File ss = new File("./SEITO_SHO_SIOT_SS.owl");
        IotOntology iontology = new IotOntology(ss);
        iontology.changeValueOfSensorMeasurement("presence", "room3MotionMeasurement");
        iontology.startReasoner();
        iontology.updateActuatorState("room3", "room3AlarmSwitch");
    }

}



