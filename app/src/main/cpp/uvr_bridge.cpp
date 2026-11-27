// C ABI declarations from sherpa-onnx v1.13.7 c-api.h (Apache-2.0).
#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <vector>
#include <algorithm>
#include <cmath>
struct SeparationConfig { struct {struct {const char *vocals,*accompaniment;} spleeter; struct {const char *model;} uvr; int32_t num_threads,debug; const char *provider;} model; };
struct SeparationStem {float **samples;int32_t num_channels,n;};
struct SeparationOutput {const SeparationStem *stems;int32_t num_stems,sample_rate;};
using Create=const void *(*)(const SeparationConfig *);
using Destroy=void (*)(const void *);
using Process=const SeparationOutput *(*)(const void *,const float *const *,int32_t,int32_t,int32_t);
using FreeOutput=void (*)(const SeparationOutput *);
struct Engine {
 void *lib=nullptr;const void *ptr=nullptr;Create create=nullptr;Destroy destroy=nullptr;Process process=nullptr;FreeOutput freeOutput=nullptr;
 bool load(){lib=dlopen("libsherpa-onnx-c-api.so",RTLD_NOW|RTLD_LOCAL);if(!lib)return false;
 create=reinterpret_cast<Create>(dlsym(lib,"SherpaOnnxCreateOfflineSourceSeparation"));
 destroy=reinterpret_cast<Destroy>(dlsym(lib,"SherpaOnnxDestroyOfflineSourceSeparation"));
 process=reinterpret_cast<Process>(dlsym(lib,"SherpaOnnxOfflineSourceSeparationProcess"));
 freeOutput=reinterpret_cast<FreeOutput>(dlsym(lib,"SherpaOnnxDestroySourceSeparationOutput"));return create&&destroy&&process&&freeOutput;}
 ~Engine(){if(ptr&&destroy)destroy(ptr);if(lib)dlclose(lib);}
};
extern "C" JNIEXPORT jboolean JNICALL Java_com_chasmet_fondvertstudio_UvrLocal_available(JNIEnv *,jclass){Engine e;return e.load()?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jlong JNICALL Java_com_chasmet_fondvertstudio_UvrLocal_create(JNIEnv *env,jclass,jstring path,jint threads){
 auto *e=new Engine;if(!e->load()){delete e;return 0;}
 const char *p=env->GetStringUTFChars(path,nullptr);if(!p){delete e;return 0;}
 SeparationConfig c{};c.model.uvr.model=p;c.model.num_threads=threads;c.model.provider="cpu";e->ptr=e->create(&c);env->ReleaseStringUTFChars(path,p);
 if(!e->ptr){delete e;return 0;}return reinterpret_cast<jlong>(e);
}
extern "C" JNIEXPORT void JNICALL Java_com_chasmet_fondvertstudio_UvrLocal_destroy(JNIEnv *,jclass,jlong handle){delete reinterpret_cast<Engine *>(handle);}
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_chasmet_fondvertstudio_UvrLocal_process(JNIEnv *env,jclass,jlong handle,jfloatArray stereo){
 auto *e=reinterpret_cast<Engine *>(handle);if(!e||!e->ptr)return nullptr;
 int n=env->GetArrayLength(stereo)/2;if(n<=0||n>16000*40)return nullptr;
 std::vector<float> inter(n*2),left(n),right(n);env->GetFloatArrayRegion(stereo,0,n*2,inter.data());
 for(int i=0;i<n;i++){left[i]=inter[i*2];right[i]=inter[i*2+1];}const float *input[]={left.data(),right.data()};
 auto *o=e->process(e->ptr,input,2,n,16000);if(!o)return nullptr;
 jfloatArray result=nullptr;
 if(o->num_stems>0&&o->sample_rate>0&&o->stems&&o->stems[0].n>0&&o->stems[0].num_channels>0){
  const auto &v=o->stems[0];std::vector<float> out(n);
  for(int i=0;i<n;i++){double pos=i*double(o->sample_rate)/16000.;int k=int(pos);if(k>=v.n)break;int next=std::min(k+1,v.n-1);float f=float(pos-k);float x=0;
   for(int c=0;c<v.num_channels;c++)x+=v.samples[c][k]*(1-f)+v.samples[c][next]*f;
   out[i]=x/v.num_channels;}
  result=env->NewFloatArray(n);if(result)env->SetFloatArrayRegion(result,0,n,out.data());
 }e->freeOutput(o);return result;
}
