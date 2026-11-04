#define _GNU_SOURCE
#include <jni.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <sys/syscall.h>
#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1
#endif
#define J(name) Java_dev_phonelinux_handshakefixture_SafeFiles_##name
static void fail(JNIEnv*e){jclass c=(*e)->FindClass(e,"java/io/IOException");(*e)->ThrowNew(e,c,"SAFE_FILE_REJECTED");}
static int nameok(const char*s){return s&&*s&&strlen(s)<=64&&!strchr(s,'/')&&strcmp(s,".")&&strcmp(s,"..");}
static int owner(int fd,int uid,int dir){struct stat s;return fstat(fd,&s)==0&&s.st_uid==(uid_t)uid&&(dir?S_ISDIR(s.st_mode):S_ISREG(s.st_mode))&&(s.st_mode&0777)==(dir?0700:0600)&&(dir||s.st_nlink==1);}
static int parentok(int fd,int uid){return owner(fd,uid,1);}
JNIEXPORT jint JNICALL J(openDirectory)(JNIEnv*e,jclass c,jstring path,jint uid){(void)c;const char*p=(*e)->GetStringUTFChars(e,path,0);int fd=-1;
 // Start from / then open EVERY path component relative with no-follow. Never follow intermediate symlinks.
 if(p&&p[0]=='/') {char*copy=strdup(p+1),*save=0,*part;if(!copy){(*e)->ReleaseStringUTFChars(e,path,p);fail(e);return -1;}fd=open("/",O_RDONLY|O_DIRECTORY|O_CLOEXEC);for(part=strtok_r(copy,"/",&save);part&&fd>=0;part=strtok_r(0,"/",&save)){if(!nameok(part)){close(fd);fd=-1;break;}int n=openat(fd,part,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);close(fd);fd=n;}free(copy);}
 if(p)(*e)->ReleaseStringUTFChars(e,path,p);
 if(fd<0||!owner(fd,uid,1)){if(fd>=0)close(fd);fail(e);return -1;}return fd;}
JNIEXPORT jint JNICALL J(makeDirectory)(JNIEnv*e,jclass c,jint fd,jstring name,jint uid){(void)c;const char*n=(*e)->GetStringUTFChars(e,name,0);int out=-1;
 if(n&&nameok(n)&&parentok(fd,uid)&&mkdirat(fd,n,0700)==0)out=openat(fd,n,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
 if(n)(*e)->ReleaseStringUTFChars(e,name,n);
 if(out<0||!owner(out,uid,1)){if(out>=0)close(out);fail(e);return -1;}return out;}
JNIEXPORT jint JNICALL J(createFile)(JNIEnv*e,jclass c,jint fd,jstring name,jbyteArray data,jint uid){(void)c;const char*n=(*e)->GetStringUTFChars(e,name,0);int out=-1;jsize len=(*e)->GetArrayLength(e,data);
 if(n&&nameok(n)&&len<=524288&&parentok(fd,uid))out=openat(fd,n,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
 if(n)(*e)->ReleaseStringUTFChars(e,name,n);
 if(out<0||!owner(out,uid,0)){if(out>=0)close(out);fail(e);return -1;}jbyte*b=(*e)->GetByteArrayElements(e,data,0);if(!b){close(out);return -1;}size_t at=0;while(at<(size_t)len){ssize_t k=write(out,b+at,len-at);
 if(k<0&&errno==EINTR)continue;
 if(k<=0)break;
 at+=k;}(*e)->ReleaseByteArrayElements(e,data,b,JNI_ABORT);int rc=fsync(out);close(out);
 if(at!=(size_t)len||rc){fail(e);return -1;}return 0;}
JNIEXPORT jint JNICALL J(createAsset)(JNIEnv*e,jclass c,jint fd,jstring name,jbyteArray data,jint uid){(void)c;const char*n=(*e)->GetStringUTFChars(e,name,0);int out=-1;jsize len=(*e)->GetArrayLength(e,data);
 if(n&&nameok(n)&&len<=16*1024*1024&&parentok(fd,uid))out=openat(fd,n,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
 if(n)(*e)->ReleaseStringUTFChars(e,name,n);
 if(out<0||!owner(out,uid,0)){if(out>=0)close(out);fail(e);return -1;}jbyte*b=(*e)->GetByteArrayElements(e,data,0);if(!b){close(out);return -1;}size_t at=0;while(at<(size_t)len){ssize_t k=write(out,b+at,len-at);
 if(k<0&&errno==EINTR)continue;
 if(k<=0)break;
 at+=k;}(*e)->ReleaseByteArrayElements(e,data,b,JNI_ABORT);int rc=fsync(out);close(out);
 if(at!=(size_t)len||rc){fail(e);return -1;}return 0;}
static void readfail(JNIEnv*e,const char*reason){jclass c=(*e)->FindClass(e,"dev/phonelinux/handshakefixture/SnapshotReadException");(*e)->ThrowNew(e,c,reason);}
JNIEXPORT jbyteArray JNICALL J(readFile)(JNIEnv*e,jclass c,jint fd,jstring name,jint uid,jint cap){(void)c;const char*n=(*e)->GetStringUTFChars(e,name,0);int in=-1,saved=0;
 if(n&&nameok(n)&&cap>0&&cap<=524288&&parentok(fd,uid)){in=openat(fd,n,O_RDONLY|O_NOFOLLOW|O_NONBLOCK|O_CLOEXEC);saved=errno;}
 if(n)(*e)->ReleaseStringUTFChars(e,name,n);
 if(in<0){readfail(e,saved==ENOENT?"NOT_PUBLISHED":saved==ELOOP?"REJECTED":"UNAVAILABLE");return 0;}
 if(!owner(in,uid,0)){close(in);readfail(e,"REJECTED");return 0;}
 unsigned char*b=malloc(cap+1);if(!b){close(in);readfail(e,"UNAVAILABLE");return 0;}size_t at=0;int io=0;
 while(at<=(size_t)cap){ssize_t k=read(in,b+at,cap+1-at);if(k<0&&errno==EINTR)continue;if(k<0){io=1;break;}if(k==0)break;at+=k;}
 struct stat st;int stable=fstat(in,&st)==0&&st.st_size==(off_t)at&&owner(in,uid,0);close(in);
 if(io||at>(size_t)cap||!stable){free(b);readfail(e,io?"UNAVAILABLE":at>(size_t)cap?"CAP_EXCEEDED":"REJECTED");return 0;}
 jbyteArray result=(*e)->NewByteArray(e,at);if(!result){free(b);return 0;}(*e)->SetByteArrayRegion(e,result,0,at,(jbyte*)b);free(b);return result;
}
JNIEXPORT void JNICALL J(publish)(JNIEnv*e,jclass c,jint fd,jstring temp,jstring target,jint uid){(void)c;const char*a=(*e)->GetStringUTFChars(e,temp,0),*b=(*e)->GetStringUTFChars(e,target,0);int good=0,in=-1,published=0,saved_errno=0;
 // No fallback: kernel/proot must support renameat2(RENAME_NOREPLACE). ENOSYS is UNAVAILABLE.
 if(a&&b&&nameok(a)&&nameok(b)&&parentok(fd,uid)){in=openat(fd,a,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
 if(in>=0&&owner(in,uid,0)){if(syscall(SYS_renameat2,fd,a,fd,b,RENAME_NOREPLACE)==0){published=1;good=fsync(fd)==0;}else saved_errno=errno;} }
 if(in>=0)close(in);
 if(a)(*e)->ReleaseStringUTFChars(e,temp,a);
 if(b)(*e)->ReleaseStringUTFChars(e,target,b);
 if(!good){if(published){jclass ex=(*e)->FindClass(e,"java/lang/IllegalStateException");(*e)->ThrowNew(e,ex,"POST_RENAME_FSYNC_INCONCLUSIVE");return;}if(saved_errno==ENOSYS||saved_errno==EOPNOTSUPP){jclass ex=(*e)->FindClass(e,"java/lang/UnsupportedOperationException");(*e)->ThrowNew(e,ex,"RENAME_NOREPLACE_UNAVAILABLE");}else fail(e);}}
JNIEXPORT void JNICALL J(close)(JNIEnv*e,jclass c,jint fd){(void)e;(void)c;
 if(fd>=0)close(fd);}

JNIEXPORT jlongArray JNICALL J(identity)(JNIEnv*e,jclass c,jint fd,jint uid,jboolean directory){
 (void)c;struct stat s;
 if(!owner(fd,uid,directory)||fstat(fd,&s)!=0){fail(e);return 0;}
 jlong data[6]={(jlong)s.st_dev,(jlong)s.st_ino,(jlong)s.st_uid,(jlong)s.st_gid,(jlong)(s.st_mode&07777),(jlong)s.st_nlink};
 jlongArray out=(*e)->NewLongArray(e,6);if(!out)return 0;(*e)->SetLongArrayRegion(e,out,0,6,data);return out;
}

JNIEXPORT void JNICALL J(remove)(JNIEnv*e,jclass c,jint fd,jstring name,jint uid){
 (void)c;const char*n=(*e)->GetStringUTFChars(e,name,0);int rc=-1;
 if(n&&nameok(n)&&parentok(fd,uid))rc=unlinkat(fd,n,0);
 if(n)(*e)->ReleaseStringUTFChars(e,name,n);
 if(rc)fail(e);
}
JNIEXPORT void JNICALL J(negative)(JNIEnv*e,jclass c,jint fd,jint op,jint uid){
 (void)c;int rc=-1,saved_errno=0;if(!parentok(fd,uid)){fail(e);return;}
 // Fixed operations only; no attacker-selected path or target.
 switch(op){
 case 1:rc=symlinkat("session",fd,"request");break;
 case 2:rc=symlinkat("session",fd,"snapshot");break;
 case 3:rc=mkdirat(fd,"request",0700);break;
 case 4:rc=mkdirat(fd,"snapshot",0700);break;
 case 5:{int f=openat(fd,"request.tmp",O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(f>=0){rc=fchmod(f,0644);close(f);}break;}
 case 7:rc=syscall(SYS_renameat2,fd,"request.tmp",fd,"request",RENAME_NOREPLACE);break;
 case 6:{int f=openat(fd,"request.tmp",O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(f>=0){rc=fchown(f,uid+1,(gid_t)-1);saved_errno=errno;close(f);}break;}
 default:break;
 }
 if(rc){
  if(op==6&&(saved_errno==EPERM||saved_errno==EACCES)){
   jclass ex=(*e)->FindClass(e,"java/lang/UnsupportedOperationException");
   (*e)->ThrowNew(e,ex,"FOREIGN_OWNER_CONSTRUCT_UNSUPPORTED");
  }else fail(e);
 }
}

JNIEXPORT void JNICALL J(removeDirectory)(JNIEnv*e,jclass c,jint fd,jstring name,jint uid){
 (void)c;const char*n=(*e)->GetStringUTFChars(e,name,0);int rc=-1;
 if(n&&nameok(n)&&parentok(fd,uid)){int child=openat(fd,n,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
  if(child>=0&&owner(child,uid,1)){rc=unlinkat(fd,n,AT_REMOVEDIR);}if(child>=0)close(child);
 }
 if(n)(*e)->ReleaseStringUTFChars(e,name,n);
 if(rc)fail(e);
}

JNIEXPORT jint JNICALL J(openChildDirectory)(JNIEnv*e,jclass c,jint parent,jstring name,jint uid){
 (void)c;const char*n=(*e)->GetStringUTFChars(e,name,0);int fd=-1;
 if(n&&nameok(n)&&parentok(parent,uid))fd=openat(parent,n,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
 if(n)(*e)->ReleaseStringUTFChars(e,name,n);
 if(fd<0||!owner(fd,uid,1)){if(fd>=0)close(fd);fail(e);return -1;}return fd;
}
JNIEXPORT void JNICALL J(executableProducer)(JNIEnv*e,jclass c,jint parent,jint uid){
 (void)c;int fd=-1,rc=-1;if(parentok(parent,uid))fd=openat(parent,"producer",O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
 if(fd>=0&&owner(fd,uid,0))rc=fchmod(fd,0700);
 if(fd>=0)close(fd);
 if(rc)fail(e);
}

JNIEXPORT jint JNICALL J(removeFixed)(JNIEnv*e,jclass c,jint fd,jstring name,jint uid){
 (void)c;const char*n=(*e)->GetStringUTFChars(e,name,0);int rc=-1,saved=0;
 if(n&&nameok(n)&&parentok(fd,uid)){
  struct stat st;
  if(fstatat(fd,n,&st,AT_SYMLINK_NOFOLLOW)!=0){saved=errno;if(saved==ENOENT)rc=1;}
  else if(st.st_uid==(uid_t)uid){
   // unlink symlink itself, never its target; directories removed only if empty.
   if(unlinkat(fd,n,S_ISDIR(st.st_mode)?AT_REMOVEDIR:0)==0)rc=0;
  }
 }
 if(n)(*e)->ReleaseStringUTFChars(e,name,n);
 if(rc<0){fail(e);return -1;}return rc;
}

JNIEXPORT jint JNICALL J(openChildOrMissing)(JNIEnv*e,jclass c,jint parent,jstring name,jint uid){
 (void)c;const char*n=(*e)->GetStringUTFChars(e,name,0);int fd=-1,saved=0;
 if(n&&nameok(n)&&parentok(parent,uid)){fd=openat(parent,n,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);saved=errno;}
 if(n)(*e)->ReleaseStringUTFChars(e,name,n);
 if(fd<0&&saved==ENOENT)return -2;
 if(fd<0||!owner(fd,uid,1)){if(fd>=0)close(fd);fail(e);return -1;}
 return fd;
}
