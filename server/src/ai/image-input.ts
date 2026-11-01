export interface AiImage {mime_type:'image/jpeg';data:string}
export const MAX_IMAGE_BYTES=128*1024;

/** Only bounded inline JPEGs: no remote URL fetching, persisted uploads or filename trust. */
export function validateImage(image:AiImage):void {
 if(!image||image.mime_type!=='image/jpeg'||typeof image.data!=='string'||image.data.length>Math.ceil(MAX_IMAGE_BYTES/3)*4||!/^[-A-Za-z0-9+/]+={0,2}$/u.test(image.data))throw Error('Invalid image');
 const bytes=Buffer.from(image.data,'base64');
 if(bytes.length>MAX_IMAGE_BYTES||bytes.toString('base64')!==image.data||bytes[0]!==255||bytes[1]!==216)throw Error('Invalid JPEG');
 let position=2;
 while(position+4<=bytes.length) {
  if(bytes[position++]!==255)throw Error('Invalid JPEG marker');
  while(bytes[position]===255)position++;
  const marker=bytes[position++]!;
  if(marker===217||marker===218)break;
  const length=bytes.readUInt16BE(position);if(length<2||position+length>bytes.length)throw Error('Invalid JPEG segment');
  if([192,193,194].includes(marker)) {
   if(length<8)throw Error('Invalid JPEG frame');
   const h=bytes.readUInt16BE(position+3),w=bytes.readUInt16BE(position+5);
   if(w<32||h<32||w>2048||h>2048||w*h>4194304)throw Error('Invalid JPEG dimensions');
   return;
  }
  position+=length;
 }
 throw Error('Missing JPEG frame');
}
