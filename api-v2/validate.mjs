// Dependency-free validator for the deliberately small JSON Schema subset used here.
// Full OpenAPI structure is separately checked by the optional standard validator.
export function validate(schema, value, root, path='$') {
  if(schema.$ref){const target=schema.$ref.split('/').slice(1).reduce((v,k)=>v?.[k.replaceAll('~1','/').replaceAll('~0','~')],root);if(!target)throw new Error(`Unresolved schema ${schema.$ref}`);return validate(target,value,root,path);}
  const errors=[];
  const fail=reason=>errors.push({field:path,reason});
  if(schema.anyOf&&!schema.anyOf.some(s=>validate(s,value,root,path).length===0))fail('허용된 값 형태가 아닙니다.');
  if(schema.oneOf&&schema.oneOf.filter(s=>validate(s,value,root,path).length===0).length!==1)fail('정확히 한 가지 형태와 일치해야 합니다.');
  const type= value===null?'null':Array.isArray(value)?'array':typeof value;
  if(schema.type && (schema.type==='integer'?!Number.isSafeInteger(value):schema.type!==type)){fail(`${schema.type} 값이어야 합니다.`);return errors;}
  if(schema.enum&&!schema.enum.some(x=>Object.is(x,value)))fail('허용된 선택값이 아닙니다.');
  if(type==='string'){
    if(schema.minLength!==undefined&&[...value].length<schema.minLength)fail('문자열이 너무 짧습니다.');
    if(schema.maxLength!==undefined&&[...value].length>schema.maxLength)fail('문자열이 너무 깁니다.');
    if(schema.pattern&&!new RegExp(schema.pattern,'u').test(value))fail('문자열 형식이 다릅니다.');
    if(schema.format==='date'&&(!/^\d{4}-\d{2}-\d{2}$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,10)!==value))fail('유효한 날짜가 아닙니다.');
    if(schema.format==='date-time'&&(!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value)||!Number.isFinite(Date.parse(value))||validate({type:'string',format:'date'},value.slice(0,10),root,path).length))fail('offset을 포함한 유효 시각이 아닙니다.');
    if(schema.format==='uri'||schema.format==='uri-reference'){try{new URL(value,...(schema.format==='uri-reference'?['https://example.invalid']:[]));}catch{fail('URL 형식이 아닙니다.');}}
  }
  if(type==='number'){
    if(!Number.isFinite(value))fail('유한 숫자여야 합니다.');
    if(schema.minimum!==undefined&&value<schema.minimum)fail('최솟값보다 작습니다.');
    if(schema.maximum!==undefined&&value>schema.maximum)fail('최댓값보다 큽니다.');
  }
  if(type==='array'){
    if(schema.minItems!==undefined&&value.length<schema.minItems)fail('배열 항목이 부족합니다.');
    if(schema.maxItems!==undefined&&value.length>schema.maxItems)fail('배열 항목이 너무 많습니다.');
    if(schema.uniqueItems&&new Set(value.map(v=>JSON.stringify(v))).size!==value.length)fail('중복 항목이 있습니다.');
    if(schema.items)value.forEach((v,i)=>errors.push(...validate(schema.items,v,root,`${path}[${i}]`)));
  }
  if(type==='object'){
    for(const key of schema.required||[])if(!Object.hasOwn(value,key))errors.push({field:`${path}.${key}`,reason:'필수 필드입니다.'});
    for(const [key,v]of Object.entries(value)){
      if(schema.properties?.[key])errors.push(...validate(schema.properties[key],v,root,`${path}.${key}`));
      else if(schema.additionalProperties===false)errors.push({field:`${path}.${key}`,reason:'정의되지 않은 필드입니다.'});
    }
  }
  return errors;
}
