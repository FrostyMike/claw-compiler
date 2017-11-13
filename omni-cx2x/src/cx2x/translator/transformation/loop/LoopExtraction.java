/*
 * This file is released under terms of BSD license
 * See LICENSE file for more information
 */

package cx2x.translator.transformation.loop;

// Cx2x import

import cx2x.translator.ClawTranslator;
import cx2x.translator.common.ClawConstant;
import cx2x.translator.common.Message;
import cx2x.translator.language.accelerator.AcceleratorHelper;
import cx2x.translator.language.base.ClawLanguage;
import cx2x.translator.language.common.ClawMapping;
import cx2x.translator.language.common.ClawMappingVar;
import cx2x.translator.transformation.ClawTransformation;
import cx2x.translator.transformation.primitive.Function;
import cx2x.translator.transformation.primitive.Loop;
import cx2x.xcodeml.exception.IllegalDirectiveException;
import cx2x.xcodeml.exception.IllegalTransformationException;
import cx2x.xcodeml.transformation.Transformation;
import cx2x.xcodeml.transformation.Translator;
import cx2x.xcodeml.xnode.*;

import java.util.Hashtable;
import java.util.List;
import java.util.Map;

// OMNI import
// Java import

/**
 * A LoopExtraction transformation is an independent transformation. The
 * transformation consists of locating a loop in a function call and extract it.
 * This loop is then wrapped around the function call and the parameters are
 * demoted accordingly to the mapping options.
 *
 * @author clementval
 */

public class LoopExtraction extends ClawTransformation {

  private final Map<String, ClawMapping> _fctMappingMap;
  private final Map<String, ClawMapping> _argMappingMap;
  private Xnode _fctCall = null;
  private Xnode _extractedLoop = null;
  private XfunctionDefinition _fctDef = null; // Fct holding the fct call
  private XfunctionDefinition _fctDefToExtract = null;

  /**
   * Constructs a new LoopExtraction triggered from a specific pragma.
   *
   * @param directive The directive that triggered the loop extraction
   *                  transformation.
   * @throws IllegalDirectiveException if something is wrong in the directive's
   *                                   options
   */
  public LoopExtraction(ClawLanguage directive)
      throws IllegalDirectiveException
  {
    super(directive);
    _argMappingMap = new Hashtable<>();
    _fctMappingMap = new Hashtable<>();

    try {
      extractMappingInformation();
    } catch(IllegalDirectiveException ide) {
      ide.setDirectiveLine(directive.getPragma().lineNo());
      throw ide;
    }
  }

  /**
   * Extract all mapping information from the pragma data. Each
   * map(<mapped>:<mapping>) produces a ClawMapping object.
   */
  private void extractMappingInformation() throws IllegalDirectiveException {
    for(ClawMapping m : _claw.getMappings()) {
      for(ClawMappingVar mappedVar : m.getMappedVariables()) {
        if(_argMappingMap.containsKey(mappedVar.getArgMapping())) {
          throw new IllegalDirectiveException(_claw.getPragma().value(),
              mappedVar + " appears more than once in the mapping");
        } else {
          _argMappingMap.put(mappedVar.getArgMapping(), m);
        }
        if(_fctMappingMap.containsKey(mappedVar.getFctMapping())) {
          throw new IllegalDirectiveException(_claw.getPragma().value(),
              mappedVar + " appears more than once in the mapping");
        } else {
          _fctMappingMap.put(mappedVar.getFctMapping(), m);
        }
      }
    }
  }

  /**
   * Check whether the provided mapping information are correct or not. A
   * mapped variable should only appear once. Mapped variable must be parameters
   * in the function definition.
   * Mapping using the same mapping variables are merged together.
   *
   * @return True if all the conditions are respected. False otherwise.
   */
  private boolean checkMappingInformation(XcodeProgram xcodeml) {
    for(Map.Entry<String, ClawMapping> map : _argMappingMap.entrySet()) {
      if(Function.findArg(_fctCall, map.getKey()) == null) {
        xcodeml.addError("Mapped variable " + map.getKey() +
                " not found in function call arguments",
            _claw.getPragma().lineNo());
        return false;
      }
    }

    return true;
  }

  /**
   * Check whether the transformation can be applied.
   *
   * @param xcodeml    The XcodeML on which the transformations are applied.
   * @param translator The translator used to applied the transformations.
   * @return True if the transformation analysis succeeded. False otherwise.
   */
  @Override
  public boolean analyze(XcodeProgram xcodeml, Translator translator) {
    Xnode _exprStmt = _claw.getPragma().matchSibling(Xcode.EXPRSTATEMENT);
    if(_exprStmt == null) {
      xcodeml.addError("No function call detected after loop-extract",
          _claw.getPragma().lineNo());
      return false;
    }

    // Find function CALL
    _fctCall = _exprStmt.matchDescendant(Xcode.FUNCTIONCALL);
    if(_fctCall == null) {
      xcodeml.addError("No function call detected after loop-extract",
          _claw.getPragma().lineNo());
      return false;
    }

    Xnode fctDef = _fctCall.matchAncestor(Xcode.FFUNCTIONDEFINITION);
    if(fctDef == null) {
      xcodeml.addError("No function around the fct call",
          _claw.getPragma().lineNo());
      return false;
    }
    _fctDef = new XfunctionDefinition(fctDef.element());

    // Find function declaration
    _fctDefToExtract = xcodeml.getFunctionDefinition(_fctCall);

    if(_fctDefToExtract == null) {
      xcodeml.addError("Could not locate the function definition for: "
              + _fctCall.matchDirectDescendant(Xcode.NAME).value(),
          _claw.getPragma().lineNo());
      return false;
    }

    // Find the loop to be extracted
    try {
      _extractedLoop = locateDoStatement(_fctDefToExtract);
    } catch(IllegalTransformationException itex) {
      xcodeml.addError(itex.getMessage(),
          _claw.getPragma().lineNo());
      return false;
    }

    return checkMappingInformation(xcodeml);
  }

  /**
   * Apply the transformation. A loop extraction is applied in the following
   * steps:
   * 1) Duplicate the function targeted by the transformation
   * 2) Extract the loop body in the duplicated function and remove the loop.
   * 3) Adapt function call and demote array references in the duplicated
   * function body.
   * 4) Optional: Add a LoopFusion transformation to the transformations'
   * queue.
   *
   * @param xcodeml        The XcodeML on which the transformations are applied.
   * @param translator     The translator used to applied the transformations.
   * @param transformation Only for dependent transformation. The other
   *                       transformation part of the transformation.
   * @throws IllegalTransformationException if the transformation cannot be
   *                                        applied.
   */
  @Override
  public void transform(XcodeProgram xcodeml, Translator translator,
                        Transformation transformation) throws Exception
  {

    ClawTranslator ct = (ClawTranslator) translator;

    /*
     * DUPLICATE THE FUNCTION
     */

    // Duplicate function definition
    XfunctionDefinition clonedFctDef = _fctDefToExtract.cloneNode();
    String newFctTypeHash =
        xcodeml.getTypeTable().generateHash(XcodeType.FUNCTION);
    String newFctName = clonedFctDef.getName() +
        ClawConstant.EXTRACTION_SUFFIX +
        translator.getNextTransformationCounter();
    clonedFctDef.name().setValue(newFctName);
    clonedFctDef.name().setType(newFctTypeHash);
    // Update the symbol table in the fct definition
    Xid fctId = clonedFctDef.getSymbolTable()
        .get(_fctDefToExtract.getName());
    fctId.setType(newFctTypeHash);
    fctId.setName(newFctName);

    // Get the fctType in typeTable
    XfunctionType fctType =
        xcodeml.getTypeTable().getFunctionType(_fctDefToExtract);
    XfunctionType newFctType = fctType.cloneNode();
    newFctType.setType(newFctTypeHash);
    xcodeml.getTypeTable().add(newFctType);

    // Get the id from the global symbols table
    Xid globalFctId = xcodeml.getGlobalSymbolsTable()
        .get(_fctDefToExtract.getName());

    // If the fct is define in the global symbol table, duplicate it
    if(globalFctId != null) {
      Xid newFctId = globalFctId.cloneNode();
      newFctId.setType(newFctTypeHash);
      newFctId.setName(newFctName);
      xcodeml.getGlobalSymbolsTable().add(newFctId);
    }

    // Insert the duplicated function declaration
    _fctDefToExtract.insertAfter(clonedFctDef);

    // Find the loop that will be extracted
    Xnode loopInClonedFct = locateDoStatement(clonedFctDef);

    Message.debug("loop-extract transformation: " + _claw.getPragma().value());
    Message.debug("  created subroutine: " + clonedFctDef.getName());

    /*
     * REMOVE BODY FROM THE LOOP AND DELETE THE LOOP
     */

    // 1. append body into fct body after loop
    Loop.extractBody(loopInClonedFct);
    // 2. delete loop
    loopInClonedFct.delete();


    /*
     * ADAPT FUNCTION CALL AND DEMOTE ARRAY REFERENCES IN THE BODY
     * OF THE FUNCTION
     */

    // Wrap function call with loop
    Xnode extractedLoop = wrapCallWithLoop(xcodeml, _extractedLoop);

    Message.debug("  call wrapped with loop: " +
        _fctCall.matchDirectDescendant(Xcode.NAME).value() + " --> " +
        clonedFctDef.getName());

    // Change called fct name
    _fctCall.matchDirectDescendant(Xcode.NAME).setValue(newFctName);
    _fctCall.matchDirectDescendant(Xcode.NAME).setType(newFctTypeHash);

    // Adapt function call parameters and function declaration
    XdeclTable fctDeclarations = clonedFctDef.getDeclarationTable();
    XsymbolTable fctSymbols = clonedFctDef.getSymbolTable();

    Message.debug("  Start to apply mapping: " + _claw.getMappings().size());

    for(ClawMapping mapping : _claw.getMappings()) {
      Message.debug("Apply mapping (" + mapping.getMappedDimensions() + ") ");
      for(ClawMappingVar var : mapping.getMappedVariables()) {
        Message.debug("  Var: " + var);
        Xnode argument = Function.findArg(_fctCall, var.getArgMapping());
        if(argument == null) {
          continue;
        }

        /* Case 1: Var --> ArrayRef
         * Var --> ArrayRef transformation
         * 1. Check that the variable used as array index exists in the
         *    current scope (XdeclTable). If so, get its type value. Create a
         *    Var element for the arrayIndex. Create the arrayIndex element
         *    with Var as child.
         *
         * 2. Get the reference type of the base variable.
         *    2.1 Create the varRef element with the type of base variable
         *    2.2 insert clone of base variable in varRef
         * 3. Create arrayRef element with varRef + arrayIndex
         */
        if(argument.opcode() == Xcode.VAR) {
          XbasicType type = xcodeml.getTypeTable().getBasicType(argument);

          // Demotion cannot be applied as type dimension is smaller
          if(type.getDimensions() < mapping.getMappedDimensions()) {
            throw new IllegalTransformationException(
                "mapping dimensions too big. Mapping " + mapping.toString() +
                    " is wrong ...", _claw.getPragma().lineNo());
          }

          Xnode newArg = xcodeml.createNode(Xcode.FARRAYREF);
          newArg.setType(type.getRef());

          Xnode varRef = xcodeml.createNode(Xcode.VARREF);
          varRef.setType(argument.getType());

          varRef.append(argument, true);
          newArg.append(varRef);

          //  create arrayIndex
          for(ClawMappingVar mappingVar : mapping.getMappingVariables()) {
            Xnode arrayIndex = xcodeml.createNode(Xcode.ARRAYINDEX);
            // Find the mapping var in the local table (fct scope)
            Xnode mappingVarDecl =
                _fctDef.getDeclarationTable().get(mappingVar.getArgMapping());

            // Add to arrayIndex
            Xnode newMappingVar =
                xcodeml.createVar(mappingVarDecl.getType(),
                    mappingVarDecl.matchSeq(Xcode.NAME).value(), Xscope.LOCAL);
            arrayIndex.append(newMappingVar);
            newArg.append(arrayIndex);
          }

          argument.insertAfter(newArg);
          argument.delete();
        }
        // Case 2: ArrayRef (n arrayIndex) --> ArrayRef (n+m arrayIndex)
        /*else if(argument.opcode() == Xcode.FARRAYREF) {
          // TODO
        }*/

        // Change variable declaration in extracted fct
        Xnode varDecl = fctDeclarations.get(var.getFctMapping());
        Xid id = fctSymbols.get(var.getFctMapping());
        XbasicType varDeclType = xcodeml.getTypeTable().getBasicType(varDecl);

        // Case 1: variable is demoted to scalar then take the ref type
        if(varDeclType.getDimensions() == mapping.getMappedDimensions()) {
          Xnode newVarDecl = xcodeml.createNode(Xcode.VARDECL);
          newVarDecl.append(xcodeml.createName(var.getFctMapping(),
              varDeclType.getRef()));
          fctDeclarations.replace(newVarDecl, var.getFctMapping());
          id.setType(varDeclType.getRef());
        }/* else {
          // Case 2: variable is not totally demoted then create new type
          // TODO

        }*/
      } // Loop mapped variables
    } // Loop over mapping clauses

    // Adapt array reference in function body
    List<Xnode> arrayReferences = clonedFctDef.body().matchAll(Xcode.FARRAYREF);
    for(Xnode ref : arrayReferences) {
      if(!(ref.matchSeq(Xcode.VARREF).child(0).opcode() == Xcode.VAR)) {
        continue;
      }
      String mappedVar = ref.matchSeq(Xcode.VARREF, Xcode.VAR).value();
      if(_fctMappingMap.containsKey(mappedVar)) {
        ClawMapping mapping = _fctMappingMap.get(mappedVar);

        boolean changeRef = true;

        int mappingIndex = 0;
        for(Xnode e : ref.children()) {
          if(e.opcode() == Xcode.ARRAYINDEX) {
            List<Xnode> children = e.children();
            if(children.size() > 0 && children.get(0).opcode() == Xcode.VAR) {
              String varName = e.matchSeq(Xcode.VAR).value();
              if(varName.equals(mapping.getMappingVariables().
                  get(mappingIndex).getFctMapping()))
              {
                ++mappingIndex;
              } else {
                changeRef = false;
              }
            }
          }
        }
        if(changeRef) {
          // TODO Var ref should be extracted only if the reference can be
          // totally demoted
          ref.insertBefore(ref.matchSeq(Xcode.VARREF, Xcode.VAR).cloneNode());
          ref.delete();
        }
      }
    }

    // Generate accelerator pragmas if needed
    AcceleratorHelper.generateAdditionalDirectives(_claw, xcodeml,
        extractedLoop, extractedLoop);
    // TODO must be triggered by a clause
    //AcceleratorHelper.generateRoutineDirectives(_claw, xcodeml, clonedFctDef);

    // Add any additional transformation defined in the directive clauses
    ct.generateAdditionalTransformation(_claw, xcodeml, extractedLoop);

    removePragma();
    transformed();
  }

  /**
   * Try to matchSeq a do statement matching the range of loop-extract.
   *
   * @param from Element to search from. Search is performed in its
   *             children.
   * @return A XdoStatement object that match the range of loop-extract.
   * @throws IllegalTransformationException When something goes wrong with loop
   *                                        detection or iteration matching.
   */
  private Xnode locateDoStatement(Xnode from)
      throws IllegalTransformationException
  {
    Xnode foundStatement = from.matchDescendant(Xcode.FDOSTATEMENT);
    if(foundStatement == null) {
      throw new IllegalTransformationException("No loop found in function",
          _claw.getPragma().lineNo());
    } else {
      if(!_claw.getRange().equals(foundStatement)) {
        // Try to match another loops that meet the criteria
        do {
          foundStatement = foundStatement.matchSibling(Xcode.FDOSTATEMENT);
        } while(foundStatement != null &&
            !_claw.getRange().equals(foundStatement));
      }
    }

    if(foundStatement == null) {
      throw new IllegalTransformationException("No loop found in function",
          _claw.getPragma().lineNo());
    }

    if(!_claw.getRange().equals(foundStatement)) {
      throw new IllegalTransformationException(
          "Iteration range is different than the loop to be extracted",
          _claw.getPragma().lineNo()
      );
    }
    return foundStatement;
  }

  /**
   * Wrap a function call with a do statement.
   *
   * @param xcodeml The XcodeML representation.
   * @param doStmt  Iteration range to be applied to the do statement.
   * @return The created do statement.
   */
  private Xnode wrapCallWithLoop(XcodeProgram xcodeml, Xnode doStmt)
  {
    // Create a new empty loop
    Xnode loop = xcodeml.createDoStmt(
        doStmt.matchDirectDescendant(Xcode.VAR).cloneNode(),
        doStmt.matchDirectDescendant(Xcode.INDEXRANGE).cloneNode());

    // Insert the new empty loop just after the pragma
    _claw.getPragma().insertAfter(loop);

    // Move the call into the loop body
    loop.body().element().
        appendChild(_fctCall.element().getParentNode());

    insertDeclaration(doStmt.matchSeq(Xcode.VAR).value());
    if(doStmt.matchSeq(Xcode.INDEXRANGE, Xcode.LOWERBOUND, Xcode.VAR) != null) {
      insertDeclaration(doStmt.
          matchSeq(Xcode.INDEXRANGE, Xcode.LOWERBOUND, Xcode.VAR).value());
    }
    if(doStmt.matchSeq(Xcode.INDEXRANGE, Xcode.UPPERBOUND, Xcode.VAR) != null) {
      insertDeclaration(doStmt.
          matchSeq(Xcode.INDEXRANGE, Xcode.UPPERBOUND, Xcode.VAR).value());
    }
    if(doStmt.matchSeq(Xcode.INDEXRANGE, Xcode.STEP, Xcode.VAR) != null) {
      insertDeclaration(doStmt.
          matchSeq(Xcode.INDEXRANGE, Xcode.STEP, Xcode.VAR).value());
    }

    return loop;
  }

  /**
   * Insert new declaration in the function definition.
   *
   * @param id The id used for insertion.
   */
  private void insertDeclaration(String id) {
    Xid inductionVarId = _fctDef.getSymbolTable().get(id);
    if(inductionVarId == null) {
      Xid copyId = _fctDefToExtract.getSymbolTable().get(id);
      _fctDef.getSymbolTable().add(copyId);
    }

    Xnode inductionVarDecl = _fctDef.getDeclarationTable().get(id);
    if(inductionVarDecl == null) {
      Xnode copyDecl = _fctDefToExtract.getDeclarationTable().get(id);
      _fctDef.getDeclarationTable().add(copyDecl);
    }
  }

  /**
   * @return Always false as independent transformation are applied one by one.
   * @see Transformation#canBeTransformedWith(XcodeProgram, Transformation)
   */
  @Override
  public boolean canBeTransformedWith(XcodeProgram xcodeml,
                                      Transformation other)
  {
    // independent transformation
    return false;
  }
}
