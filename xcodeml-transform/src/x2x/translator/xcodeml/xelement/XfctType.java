package x2x.translator.xcodeml.xelement;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * The XfctType represents the FfunctionType (3.4) element in XcodeML
 * intermediate representation.
 *
 * Elements:
 * - Optional:
 *   - params TODO
 * Attributes:
 * - Required: type (text), return_type (text)
 * - Optional: result_name (text), is_recursive (bool), is_program (bool),
 *   is_internal (bool)
 */

public class XfctType extends Xtype implements Xclonable<XfctType> {

  private String _returnType = null;
  private boolean _isProgram = false;

  // attributes
  private String _result_name = null;
  private boolean _is_recursive = false;
  private boolean _is_program = false;
  private boolean _is_internal = false;

  public XfctType(Element element){
    super(element);
    readFctTypeInformation();
  }

  private void readFctTypeInformation(){
    _returnType = XelementHelper.getAttributeValue(baseElement,
      XelementName.ATTR_RETURN_TYPE);

    String value = XelementHelper.getAttributeValue(baseElement,
      XelementName.ATTR_IS_PROGRAM);

    if(value != null && value.equals(XelementName.TRUE)){
      _isProgram = true;
    }

    // TODO read parameters as Xname element

    // read optional attributes
    _result_name = XelementHelper.getAttributeValue(baseElement,
      XelementName.ATTR_RESULT_NAME);
    _is_recursive = XelementHelper.getBooleanAttributeValue(baseElement,
      XelementName.ATTR_IS_RECURSIVE);
    _is_program = XelementHelper.getBooleanAttributeValue(baseElement,
        XelementName.ATTR_IS_PROGRAM);
    _is_internal = XelementHelper.getBooleanAttributeValue(baseElement,
        XelementName.ATTR_IS_INTERNAL);
  }

  public String getReturnType(){
    return _returnType;
  }

  public boolean isProgram(){
    return _isProgram;
  }

  public XfctType cloneObject(){
    Node clone = clone();
    return new XfctType((Element)clone);
  }

}
