package spinach.argumentclassifier;

import com.google.common.collect.Sets;
import edu.stanford.nlp.classify.Dataset;
import edu.stanford.nlp.ling.BasicDatum;
import edu.stanford.nlp.ling.Datum;
import edu.stanford.nlp.stats.Counter;
import spinach.argumentclassifier.featuregen.ArgumentFeatureGenerator;
import spinach.argumentclassifier.featuregen.ExtensibleFeatureGenerator;
import spinach.classifier.PerceptronClassifier;
import spinach.sentence.SemanticFrameSet;
import spinach.sentence.Token;
import spinach.sentence.TokenSentence;
import spinach.sentence.TokenSentenceAndPredicates;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * ArgumentClassifiers, given a sentence and a set of predicates,
 * identify the arguments of each predicate in that sentence
 * and the semantic relation between each predicate and argument.
 *
 * @author Calvin Huang
 */
public abstract class ArgumentClassifier implements Serializable {

    private boolean enableConsistency = true;
    private boolean consistencyWhenTraining;

    protected final PerceptronClassifier classifier;
    private final ArgumentFeatureGenerator featureGenerator;

    public final static String NIL_LABEL = "NIL";

    private static final long serialVersionUID = 1008397275270930536L;

    private static final int FEATURE_COUNT_THRESHOLD = 3;

    /**
     * Constructs an ArgumentClassifier with a perceptron and a feature generator
     *
     * @param classifier       a Perceptron classifier that this ArgumentClassifier is based upon
     * @param featureGenerator that generates features for each input
     */
    protected ArgumentClassifier(PerceptronClassifier classifier, ArgumentFeatureGenerator featureGenerator) {
        this.classifier = classifier;
        this.featureGenerator = featureGenerator;
    }

    /**
     * Constructs a SemanticFrameSet from a sentence with predicates
     *
     * @param sentenceAndPredicates sentence with predicates
     * @return initial sentence with predicates with added arguments
     */
    public SemanticFrameSet framesWithArguments(TokenSentenceAndPredicates sentenceAndPredicates) {
        return framesWithArguments(sentenceAndPredicates, false);
    }

    /**
     * Constructs a SemanticFrameSet from a sentence with predicates with training weights
     * For use in training only
     *
     * @param sentenceAndPredicates sentence with predicates
     * @return initial sentence with predicates with added arguments
     */
    public SemanticFrameSet trainingFramesWithArguments(TokenSentenceAndPredicates sentenceAndPredicates) {
        return framesWithArguments(sentenceAndPredicates, true);
    }

    protected abstract SemanticFrameSet framesWithArguments(TokenSentenceAndPredicates sentenceAndPredicates,
                                                            boolean training);

    /**
     * Returns all the possible argument candidates for a given sentence and predicate
     *
     * @param sentence  sentence to analyze
     * @param predicate predicate to find argument candidates of
     * @return list of possible argument candidates, in order
     */
    public static List<Token> argumentCandidates(TokenSentence sentence, Token predicate) {
        List<Token> argumentCandidates = new ArrayList<Token>();
        Token currentHead = predicate;
        while (currentHead != null) {
            argumentCandidates.addAll(sentence.getChildren(currentHead));
            if (currentHead.headSentenceIndex < 0) {
                argumentCandidates.add(currentHead);
                break;
            }
            currentHead = sentence.getParent(currentHead);
        }

        Collections.sort(argumentCandidates, new Comparator<Token>() {
            @Override
            public int compare(Token t1, Token t2) {
                return new Integer(t1.sentenceIndex).
                        compareTo(t2.sentenceIndex);
            }
        });

        return argumentCandidates;
    }

    /**
     * Gives the scores for each argument label, given a sentence, predicate, and argument candidate
     *
     * @param frameSet    sentence to be analyzed
     * @param possibleArg possible argument of that sentence
     * @param predicate   predicate in that sentence
     * @param training    whether or not you want training weights
     * @return scores of the possible labels of that predicate-argument pair
     */
    protected Counter<String> argClassScores(SemanticFrameSet frameSet, Token possibleArg, Token predicate,
                                             boolean training) {
        Datum<String, String> d = featureGenerator.datumFrom(frameSet, possibleArg, predicate);
        return training ? classifier.trainingScores(d) :
                classifier.scoresOf(d);
    }

    protected void updateCounterScores(SemanticFrameSet frameSet, Token possibleArg, Token predicate,
                                       Counter<String> scores, boolean training) {
        classifier.updateCounterScores(featureGenerator.datumFrom(frameSet, possibleArg, predicate), scores, training);
    }

    /**
     * Generates a dataset (to be used in training) for a given frameset
     *
     * @param frameSet frameset to analyze
     * @return Dataset with features generated from the frameset
     */
    private Dataset<String, String> datasetFrom(SemanticFrameSet frameSet) {
        Dataset<String, String> dataset = new Dataset<String, String>();
        for (Token predicate : frameSet.getPredicateList()) {
            for (Token argument : argumentCandidates(frameSet, predicate)) {
                BasicDatum<String, String> datum =
                        (BasicDatum<String, String>) featureGenerator.datumFrom(frameSet, argument, predicate);
                String label = NIL_LABEL;
                for (Map.Entry<Token, String> p : frameSet.argumentsOf(predicate).entrySet()) {
                    if (p.getKey().equals(argument)) {
                        label = p.getValue();
                        break;
                    }
                }
                datum.setLabel(label);
                dataset.add(datum);
            }
        }

        return dataset;
    }

    /**
     * Trains on a bunch of SemanticFrameSets.
     *
     * @param frameSets Collection of framesets to generate a dataset
     */
    public void unstructuredTrain(Collection<SemanticFrameSet> frameSets) {
        Dataset<String, String> dataset = new Dataset<String, String>();
        for (SemanticFrameSet frameSet : frameSets)
            dataset.addAll(datasetFrom(frameSet));

        dataset.applyFeatureCountThreshold(FEATURE_COUNT_THRESHOLD);

        classifier.train(dataset);
    }

    /**
     * Update the prediction model based on a known gold frame, and a predicted frame.
     *
     * @param predictedFrame frame predicted by this model
     * @param goldFrame      known labels for sentence
     */
    public void update(SemanticFrameSet predictedFrame, SemanticFrameSet goldFrame) {
        Dataset<String, String> dataset = new Dataset<String, String>();

        for (Token predicate : goldFrame.getPredicateList()) {

            if (predictedFrame.isPredicate(predicate)) {
                Map<Token, String> goldArguments = goldFrame.argumentsOf(predicate);
                Map<Token, String> predictedArguments = predictedFrame.argumentsOf(predicate);

                for (Token t : argumentCandidates(predictedFrame, predicate)) {
                    String goldLabel = goldArguments.get(t);
                    String predictedLabel = predictedArguments.get(t);

                    if (goldLabel == null)
                        goldLabel = NIL_LABEL;

                    if (predictedLabel == null)
                        predictedLabel = NIL_LABEL;

                    BasicDatum<String, String> datum =
                            (BasicDatum<String, String>) featureGenerator.datumFrom(predictedFrame, t, predicate);

                    datum.setLabel(PerceptronClassifier.formatManualTrainingLabel(predictedLabel, goldLabel));

                    dataset.add(datum);

                }

            } else {
                Map<Token, String> goldArguments = goldFrame.argumentsOf(predicate);

                for (Token t : argumentCandidates(predictedFrame, predicate)) {
                    String goldLabel = goldArguments.get(t);
                    String predictedLabel = NIL_LABEL;

                    if (goldLabel == null)
                        goldLabel = NIL_LABEL;

                    BasicDatum<String, String> datum =
                            (BasicDatum<String, String>) featureGenerator.datumFrom(predictedFrame, t, predicate);

                    datum.setLabel(PerceptronClassifier.formatManualTrainingLabel(predictedLabel, goldLabel));

                    dataset.add(datum);
                }
            }
        }
        classifier.manualTrain(dataset);
    }

    void enforceConsistency(Token predicate, Token arg, String argLabel, SemanticFrameSet frameSet,
                            boolean training, Map<Token, Counter<String>> argumentLabelScores) {
        if (enableConsistency && (!training || consistencyWhenTraining)) {
            if (isRestrictedLabel(argLabel)) {
                for (Token token : argumentLabelScores.keySet())
                    argumentLabelScores.get(token).remove(argLabel);

                if (arg.equals(predicate))
                    return;

                Set<Token> restrictedTokens = ancestorsNotCrossingPredicate(arg, predicate, frameSet);
                restrictedTokens.addAll(descendantsNotCrossingPredicate(arg, predicate, frameSet));

                for (Token t : Sets.intersection(restrictedTokens, argumentLabelScores.keySet()))
                    for (Iterator<String> itr = argumentLabelScores.get(t).keySet().iterator(); itr.hasNext(); )
                        if (isRestrictedLabel(itr.next()))
                            itr.remove();
            }
        }
    }

    private static boolean isRestrictedLabel(String label) {
        return label.matches("A[0-9]");
    }

    private static Set<Token> ancestorsNotCrossingPredicate(Token arg, Token predicate, SemanticFrameSet frameSet) {
        Set<Token> ancestors = new HashSet<Token>();
        Token t = frameSet.getParent(arg);
        while (t != null && !predicate.equals(t)) {
            ancestors.add(t);
            t = frameSet.getParent(t);
        }
        return ancestors;
    }

    private static Set<Token> descendantsNotCrossingPredicate(Token arg, Token predicate, SemanticFrameSet frameSet) {
        Set<Token> descendants = new HashSet<Token>();
        List<Token> children = frameSet.getChildren(arg);
        descendants.addAll(children);
        for (Token child : children) {
            if (!child.equals(predicate))
                descendants.addAll(descendantsNotCrossingPredicate(child, predicate, frameSet));
            else
                descendants.remove(child);
        }

        return descendants;
    }

    /**
     * Get the feature generator for this argument classifier.
     *
     * @return this classifier's feature generator
     */
    public ArgumentFeatureGenerator getFeatureGenerator() {
        return featureGenerator;
    }

    /**
     * Tells if the feature generator for this argument classifier can be trained.
     *
     * @return whether or not feature generator can be trained
     */
    public boolean isFeatureTrainable() {
        return featureGenerator instanceof ExtensibleFeatureGenerator;
    }

    /**
     * Resets the perceptron for this classifier so that it can be retrained.
     */
    public void reset() {
        classifier.reset();
    }

    /**
     * Updates the average weights for the classifier. Must be done in order.
     * to perform classification.
     */
    public void updateAverageWeights() {
        classifier.updateAverageWeights();
    }

    /**
     * Extracts the set of argument labels from a set of sentences.
     *
     * @param frameSets set of sentences
     * @return collection of labels encountered in those sentences (plus NIL label)
     */
    public static Collection<String> getLabelSet(Collection<SemanticFrameSet> frameSets) {
        List<String> labels = new ArrayList<String>();
        labels.add(NIL_LABEL);
        for (SemanticFrameSet s : frameSets)
            for (Token predicate : s)
                labels.addAll(s.argumentsOf(predicate).values());

        return labels;
    }

    /**
     * Whether or not to enable checks for consistency (i.e. following semantic rules),
     * and whether or not to do so during training.
     *
     * @param enableConsistency       whether or not to enable consistency
     * @param consistencyWhenTraining whether or not to enable consistency during training
     */
    public void setConsistencyMode(boolean enableConsistency, boolean consistencyWhenTraining) {
        this.enableConsistency = enableConsistency;
        this.consistencyWhenTraining = consistencyWhenTraining;
    }

    /**
     * Loads an argument classifier.
     *
     * @param filePath file to load classifier from
     * @return imported classifier
     * @throws IOException            if failed to load
     * @throws ClassNotFoundException if class found is not an ArgumentClassifier
     */
    public static ArgumentClassifier importClassifier(String filePath)
            throws IOException, ClassNotFoundException {
        ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(filePath))));

        return (ArgumentClassifier) in.readObject();
    }

    /**
     * Saves this argument classifier.
     *
     * @param filePath file to save classifier to
     * @throws IOException if failed to export
     */
    public void exportClassifier(String filePath) throws IOException {
        ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(filePath))));
        out.writeObject(this);
        out.close();
    }
}
