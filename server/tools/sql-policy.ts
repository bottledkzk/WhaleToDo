import ts from 'typescript';
import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
export function auditSql(source:string,file='source.ts'):string[]{
 const tree=ts.createSourceFile(file,source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);
 const errors:string[]=[];
 function method(n:ts.Node):string|undefined{
  if(ts.isPropertyAccessExpression(n))return n.name.text;
  if(ts.isElementAccessExpression(n)&&n.argumentExpression&&ts.isStringLiteral(n.argumentExpression))return n.argumentExpression.text;
  if(ts.isIdentifier(n))return n.text;
 }
 function report(n:ts.Node,message:string){const pos=tree.getLineAndCharacterOfPosition(n.getStart(tree));errors.push(`${file}:${pos.line+1} ${message}`);}
 function visit(n:ts.Node){
  if(ts.isCallExpression(n)){
   const name=method(n.expression);
   if(name==='$queryRawUnsafe'||name==='$executeRawUnsafe'||name==='raw')report(n,'Unsafe/raw SQL construction is forbidden in runtime source');
   if(name==='query'){
    const sql=n.arguments[0];
    if(!sql||!(ts.isStringLiteral(sql)||ts.isNoSubstitutionTemplateLiteral(sql)))report(n,'pg.query SQL must be an inline static literal; pass data separately as $n values');
   }
   if(name==='$queryRaw'||name==='$executeRaw'){
    const sql=n.arguments[0];
    if(!sql||!ts.isTaggedTemplateExpression(sql)||method(sql.tag)!=='sql')report(n,'Use a direct Prisma tagged template or Prisma.sql tagged template');
   }
  }
  ts.forEachChild(n,visit);
 }
 visit(tree);return errors;
}
export function auditDirectory(dir:string):string[]{
 const errors:string[]=[];
 for(const e of readdirSync(dir,{withFileTypes:true})){
  const path=join(dir,e.name);
  if(e.isDirectory())errors.push(...auditDirectory(path));
  else if(/\.[cm]?[jt]s$/.test(e.name))errors.push(...auditSql(readFileSync(path,'utf8'),relative(process.cwd(),path)));
 }
 return errors;
}
if(process.argv[1]&&fileURLToPath(import.meta.url)===process.argv[1]){
 const errors=auditDirectory(fileURLToPath(new URL('../src/',import.meta.url)));
 if(errors.length){for(const e of errors)console.error(e);process.exitCode=1;}
 else console.log('Runtime SQL syntax policy passed (Prisma tags / static pg SQL + parameters).');
}
